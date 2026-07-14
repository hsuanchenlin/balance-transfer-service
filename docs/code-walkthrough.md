# Complete code walkthrough - every file explained

This document explains every piece of the codebase: each source file, each test
class, the schema, and the configuration. Read it top to bottom once and you
can defend any line of this project.

Companion docs, each with a different job:

- [README.md](../README.md) - the design rationale (the "why" behind the five
  load-bearing decisions) and the API reference.
- [docs/interview-qa.md](interview-qa.md) - a 5-minute code map plus answers to
  15 interviewer questions. Read that for the narrative; read this file for
  exhaustive coverage.
- [CONTEXT.md](../CONTEXT.md) and [docs/adr/](adr/) - the domain glossary and
  the two architecture decision records the code keeps citing.

## Suggested reading order

1. [`init.sql`](../init.sql) - the three tables; every invariant starts here.
2. [`TransferService`](../src/main/java/com/example/demo/service/TransferService.java) - the heart; everything else serves it.
3. [`AccountRepository`](../src/main/java/com/example/demo/repository/AccountRepository.java) and [`TransferRepository`](../src/main/java/com/example/demo/repository/TransferRepository.java) - the exact SQL the service relies on.
4. Controllers, exceptions, cache, events - the shell around the core.
5. The test suite - each class maps to one guarantee.

## The 30-second architecture

One Spring Boot app, three backing services from `docker-compose.yaml`:

| Service | Role | Port |
|---|---|---|
| MySQL 8.0 | The single correctness authority (ADR-0001). All money state. | 3306 |
| Redis 7 | Read-path balance cache only. Never consulted for decisions. | 6379 |
| RocketMQ 5.1.4 (namesrv + broker + console) | Carries async side-effects (audit log), fed by a transactional outbox, at-least-once. | 9876 (namesrv) / 10911 (broker) / 8088 (console UI) |

Request flow: `controller` validates shape, `service` owns the transaction and
the business rules, `repository` runs one explicit SQL statement per method.
The transfer's event is appended to the `outbox_event` table inside the same
transaction as the money movement; after the commit an `afterCommit` hook
evicts the Redis cache, and the scheduled `OutboxRelay` publishes the row to
RocketMQ, whose consumer writes an idempotent `audit_log` row.

## Database schema ([`init.sql`](../init.sql))

Mounted into the MySQL container via `docker-compose.yaml`, so it runs once on
first container start.

### `account` - the authoritative balances (mutable)

| Column | Type | Why |
|---|---|---|
| `user_id` | `VARCHAR(64)` PK | Natural key; the assignment says userId is unique. |
| `balance` | `DECIMAL(19,4)` | Exact decimal money, never floating point. 4 decimal places of sub-cent precision. |
| `created_at` / `updated_at` | `TIMESTAMP` | Bookkeeping; `updated_at` auto-updates. |

`CHECK (balance >= 0)` is a last-line-of-defense invariant: even if every
application guard failed, MySQL would refuse a negative balance.

### `transfer` - append-only history + idempotency + cancel linkage

| Column | Why |
|---|---|
| `id` | Auto-increment PK; returned to clients as `transferId`. |
| `from_user_id`, `to_user_id` | FKs to `account`; each has a secondary index for history lookups. |
| `amount` | `DECIMAL(19,4)`, `CHECK (amount > 0)`. |
| `status` | `COMPLETED` or `CANCELLED`. The guarded cancel flips this. |
| `request_id` | Nullable idempotency key under `UNIQUE KEY uq_transfer_request_id`. NULLs are allowed and not deduplicated (MySQL unique indexes permit many NULLs), so requests without a key are never blocked. |
| `reversal_of` | Self-FK. NULL for originals; on a compensating reversal it points at the cancelled transfer. This is how cancel stays append-only (ADR-0002). |
| `created_at` | Indexed; drives history ordering and the 10-minute cancel window. |

### `audit_log` - written asynchronously by the MQ consumer

`UNIQUE KEY uq_audit_event (event_type, transfer_id)` plus `INSERT IGNORE` in
the repository makes redelivery idempotent: RocketMQ is at-least-once, so the
same event may arrive twice, but only one row lands. The key includes
`event_type` so a `TransferCompleted` and a `TransferCancelled` row for the
same transfer can coexist.

### `outbox_event` - the transactional outbox feeding RocketMQ

| Column | Why |
|---|---|
| `id` | Auto-increment PK; also the relay's publish order. |
| `event_type` | `TransferCompleted` / `TransferCancelled`; doubles as the RocketMQ message tag. |
| `payload` | The event DTO as JSON - the exact message body the relay sends. |
| `attempts` | Failed publish attempts so far; drives the backoff. |
| `next_attempt_at` | Earliest time the relay may (re)try the row; a failure pushes it out 2s, 4s, ... capped at 60s. |
| `published_at` | NULL until delivered; the relay's `WHERE published_at IS NULL` scan key (indexed with `id`). |

A row is inserted in the *same transaction* as the transfer or cancel it
describes, so the event exists iff the business change committed - that is
the outbox guarantee. The relay marks the row published only after the broker
accepts it; a crash in between re-sends (at-least-once), which the idempotent
consumer absorbs.

## Main code, file by file

### Entry point

- [`DemoApplication`](../src/main/java/com/example/demo/DemoApplication.java) -
  the standard `@SpringBootApplication` main class. Nothing custom.

### `controller/` - HTTP in and out, zero business logic

- [`UserController`](../src/main/java/com/example/demo/controller/UserController.java) -
  `POST /users` (201, empty body) and `GET /users/{userId}/balance`. Delegates
  straight to `UserService`.
- [`TransferController`](../src/main/java/com/example/demo/controller/TransferController.java) -
  `POST /transfers` (201), `GET /transfers?userId=&page=&size=` and
  `POST /transfers/{transferId}/cancel`. Note the class-level `@Validated`:
  that is what makes the `@Min`/`@Max`/`@NotBlank` annotations on the *query
  parameters* fire (bean validation on request bodies only needs `@Valid`, but
  parameter-level constraints need `@Validated` on the class, and violations
  surface as `ConstraintViolationException`, handled in the exception layer).
  One deliberate quirk: an idempotent replay also returns 201, because a retry
  is the same logical creation - the handler has one success path.

### `model/` - immutable request/response records

All six are Java records: immutable, value-semantics, no boilerplate.

- [`CreateUserRequest`](../src/main/java/com/example/demo/model/CreateUserRequest.java) -
  `userId` (`@NotBlank`) + `initialBalance` (`@PositiveOrZero`,
  `@Digits(integer = 15, fraction = 4)`). The `@Digits` bound is deliberately
  stricter than the column's `DECIMAL(19,4)`, leaving headroom so a maximal
  balance can still receive credits without overflowing the column.
- [`TransferRequest`](../src/main/java/com/example/demo/model/TransferRequest.java) -
  parties (`@NotBlank`), `amount` (`@Positive`, same `@Digits`), and an
  *optional* `requestId` (no annotation: idempotency is opt-in).
- [`TransferResponse`](../src/main/java/com/example/demo/model/TransferResponse.java) -
  `{transferId, status}`, returned by both transfer and cancel.
- [`BalanceResponse`](../src/main/java/com/example/demo/model/BalanceResponse.java) -
  `{userId, balance}`.
- [`TransferHistoryItem`](../src/main/java/com/example/demo/model/TransferHistoryItem.java) -
  one history row; `reversalOf` is `Long` (nullable) and set only on
  compensating reversals. Also reused internally as the return type of the
  repository's single-row lookups.
- [`PageResponse<T>`](../src/main/java/com/example/demo/model/PageResponse.java) -
  generic `{content, page, size, totalElements}` envelope for history.

### `service/` - business rules and transaction boundaries

- [`UserService`](../src/main/java/com/example/demo/service/UserService.java) -
  two methods, no `@Transactional` needed (each is a single statement).
  `createUser` translates the PK violation (`DuplicateKeyException`) into
  `UserAlreadyExistsException` (409) - the database is the uniqueness
  authority, not a check-then-insert. `getBalance` is textbook cache-aside:
  try `BalanceCache`, on miss read MySQL, populate the cache, return.

- [`TransferService`](../src/main/java/com/example/demo/service/TransferService.java) -
  the heart of the assignment. Three public methods, each one transaction:

  **`transfer(request)`** (`@Transactional`), in order:
  1. Reject self-transfer, reject unknown sender or receiver (fast 4xx before
     touching money).
  2. Idempotency replay: if `requestId` is set and a transfer already exists
     under it, compare payloads. Same parties and same amount (compared with
     `BigDecimal.compareTo`, so `100` equals `100.0000` from the DECIMAL
     column) replays the original `{transferId, status}`; a mismatch throws
     `IdempotencyConflictException` (422) because reusing a key for a
     different request is a client bug, not a retry.
  3. `moveInLockOrder(from, to, amount)` moves the money (see below).
  4. Insert the `transfer` row. If the `UNIQUE(request_id)` constraint fires
     here, a concurrent duplicate won the race. The loser re-reads the winning
     row with a locking read (`FOR SHARE`, because the plain-read snapshot
     predates the winner's commit) and classifies exactly like step 2: same
     payload throws `DuplicateRequestException` (409), a different payload
     throws `IdempotencyConflictException` (422). The locking read is
     best-effort: the loser still holds both account-row locks while a
     concurrent cancel of the winner locks the transfer row first, so on a
     deadlock or lock-wait timeout the loser falls back to the conservative
     409 (a retry then gets the precise answer via step 2) instead of
     surfacing a 500. Either way this attempt's balance changes roll back -
     the transfer applies at most once.
  5. Append a `TransferCompletedEvent` to the outbox (`TransferOutbox`) inside
     the same transaction - the event commits iff the transfer does - and
     register an `afterCommit` hook that evicts both cached balances. Nothing
     publishes from the request path, and the write transaction never blocks
     on Redis or RocketMQ; delivery is the relay's job.

  **`history(userId, page, size)`** (`@Transactional(readOnly = true)`) -
  clamps size to `MAX_PAGE_SIZE` (100) as a service-level backstop even though
  the controller already validates it, computes the offset, and returns a
  `PageResponse` with the total count.

  **`cancel(transferId)`** (`@Transactional`) - cancel as compensation
  (ADR-0002), never a delete:
  1. `markCancelled` attempts the guarded status flip first. The `UPDATE ...
     WHERE status = 'COMPLETED' AND reversal_of IS NULL AND created_at >
     NOW() - INTERVAL 10 MINUTE` is atomic check-and-set: it also takes the
     row lock, so two concurrent cancels serialize and only one flips.
  2. If 0 rows flipped, classify why with `findByIdForUpdate` (a locking read
     that sees the latest committed state, not this transaction's snapshot):
     missing means 404, already `CANCELLED` means idempotent 200 replay,
     otherwise 409 (outside the window, or the target is itself a reversal).
  3. If flipped, read the original and move the money back, receiver to
     sender, through the same `moveInLockOrder`. The conditional debit means
     a receiver who already spent the money yields `InsufficientFundsException`
     (409) and the whole cancel, including the status flip, rolls back.
  4. Append the reversal row (`insertReversal`, linked via `reversal_of`),
     append the `TransferCancelledEvent` to the outbox in the same
     transaction, and register the same afterCommit eviction hook.

  **The private helpers** encode the two core safety mechanisms:
  - `moveInLockOrder` touches the two account rows in ascending `userId`
    order, whichever direction the money flows. Concurrent A→B and B→A
    transfers therefore acquire row locks in the same order and cannot
    deadlock.
  - `debitOrThrow` maps 0 affected rows to `InsufficientFundsException`; the
    conditional `UPDATE ... WHERE balance >= amount` is the atomic
    check-and-decrement (ADR-0001).
  - `creditOrThrow` maps 0 affected rows to `IllegalStateException`. It is
    unreachable today (existence is pre-checked, accounts are never deleted)
    but keeps the money path loud-by-default against future refactors: a
    dropped credit must never be silent.
  - `afterCommit(Runnable)` wraps
    `TransactionSynchronizationManager.registerSynchronization` so the two
    call sites stay declarative.

### `repository/` - one explicit SQL statement per method (`JdbcClient`)

Explicit SQL is a deliberate tech choice (see README "Tech-choice note"): the
correctness story lives in the exact SQL, so it is kept visible.

- [`AccountRepository`](../src/main/java/com/example/demo/repository/AccountRepository.java) -
  `insert`, `findBalance`, `exists`, and the two money primitives. `debit` is
  the load-bearing statement of the project:
  `UPDATE account SET balance = balance - :amount WHERE user_id = :userId AND
  balance >= :amount`. The relative update plus the guard inside one statement
  means InnoDB's row lock makes check-and-decrement indivisible; 0 rows means
  insufficient funds. `credit` is the unconditional counterpart.
- [`TransferRepository`](../src/main/java/com/example/demo/repository/TransferRepository.java) -
  `insertCompleted` (returns the generated key; the UNIQUE `request_id` is the
  concurrency-safe idempotency gate), `insertReversal` (same insert with
  `reversal_of` instead of `request_id`), `findByRequestId` (full payload, so
  replay can verify the retry matches), `findById`, `findByIdForUpdate`
  (`FOR UPDATE` so a failed cancel is classified against the latest committed
  state), `listByUser` (OR over sender/receiver, `ORDER BY created_at DESC,
  id DESC` for a stable total order when timestamps collide), `countByUser`,
  and `markCancelled` (the guarded flip described above). The private
  `mapItem` row mapper has the one subtle JDBC point in the codebase:
  `rs.wasNull()` must be checked immediately after `rs.getLong("reversal_of")`
  because it reflects only the most recent column read.
- [`AuditRepository`](../src/main/java/com/example/demo/repository/AuditRepository.java) -
  `recordOnce` uses `INSERT IGNORE` against the `(event_type, transfer_id)`
  unique key, making consumer redelivery idempotent. `countByTransferId`
  exists for tests.
- [`OutboxRepository`](../src/main/java/com/example/demo/repository/OutboxRepository.java) -
  the four outbox primitives: `append` (joins the caller's transaction - the
  point of the pattern), `findDue` (unpublished rows whose backoff has
  elapsed, in id order), `markPublished`, and `markFailed` (increments
  `attempts` and defers via `next_attempt_at` with a capped exponential
  backoff computed in SQL).

### `cache/` - Redis, read path only

- [`BalanceCache`](../src/main/java/com/example/demo/cache/BalanceCache.java) -
  wraps `StringRedisTemplate` with keys `balance:{userId}`, values stored via
  `BigDecimal.toPlainString()`, TTL 5 minutes. Two properties matter:
  - *Fail-open*: every operation catches `DataAccessException` and degrades
    (a failed `get` is a miss, a failed `put`/`evict` is a logged no-op), and
    a cached value that fails to parse as a number is evicted and treated as
    a miss rather than throwing on every read.
    Because the DB is the authority, a Redis outage must never fail a request;
    before this hardening, a dead Redis 500'd balance reads and even committed
    transfers (the afterCommit eviction threw).
  - The TTL bounds the staleness any missed eviction can cause.

### `event/` - RocketMQ DTOs, transactional outbox, consumer handler

- [`TransferOutbox`](../src/main/java/com/example/demo/event/TransferOutbox.java) -
  the request-path half of the outbox and the only messaging type the service
  depends on: serializes the event with Jackson and appends it via
  `OutboxRepository`, inside the business transaction. A serialization failure
  fails the transaction (committing a transfer whose event can never be
  delivered would silently break at-least-once).
- [`OutboxRelay`](../src/main/java/com/example/demo/event/OutboxRelay.java) -
  the delivery half: a `@Scheduled` poller (1s `fixedDelay`) that reads due
  unpublished rows in id order, sends each through `OutboxMessageSender`, and
  stamps `published_at`. A failed send defers only that row (`markFailed`:
  attempts + capped exponential backoff), so a poisoned event cannot block
  the queue. Its javadoc holds the scheduling/locking notes: `fixedDelay`
  passes never overlap in one instance, multiple instances are safe because
  the worst case is a duplicate send (idempotent consumer), and production
  relay coordination would use `FOR UPDATE SKIP LOCKED` or ShedLock.
- [`OutboxMessageSender`](../src/main/java/com/example/demo/event/OutboxMessageSender.java) -
  the broker-facing seam: `send(eventType, payload)`, where throwing means
  "not delivered, retry later". The pair mirrors the old publisher pair:
  [`RocketMqOutboxSender`](../src/main/java/com/example/demo/event/RocketMqOutboxSender.java)
  (active when `rocketmq.enabled=true`; sends to the configured topic with
  the event type as the message *tag*, failures propagate to the relay) and
  [`NoOpOutboxSender`](../src/main/java/com/example/demo/event/NoOpOutboxSender.java)
  (`matchIfMissing = true`, so also the default: the relay drains the outbox
  to nowhere). This seam is why the test suite runs without a broker.
- [`OutboxEvent`](../src/main/java/com/example/demo/event/OutboxEvent.java) -
  a pending outbox row as the relay sees it: id, event type, payload,
  attempts.
- [`TransferCompletedEvent`](../src/main/java/com/example/demo/event/TransferCompletedEvent.java) /
  [`TransferCancelledEvent`](../src/main/java/com/example/demo/event/TransferCancelledEvent.java) -
  message payload records. The cancelled event carries both the original
  `transferId` (the audit key) and the `reversalId`, plus the original
  parties so the consumer can evict both caches.
- [`TransferEventHandler`](../src/main/java/com/example/demo/event/TransferEventHandler.java) -
  the consumer-side logic, kept broker-agnostic (plain component, unit
  testable): write the idempotent audit row, evict both balances. It also owns
  the event-type constants that double as RocketMQ tags. Cancelled events are
  keyed on the *original* transfer id, so completed and cancelled audit rows
  for the same transfer coexist under the composite unique key.

### `config/`

- [`RocketMqConfig`](../src/main/java/com/example/demo/config/RocketMqConfig.java) -
  gated on
  `@ConditionalOnProperty("rocketmq.enabled")` so tests never start it. Wires
  the raw `DefaultMQProducer` (started/stopped via bean lifecycle) and a
  `DefaultMQPushConsumer` whose listener routes by message tag to the handler,
  returning `RECONSUME_LATER` on any exception so RocketMQ redelivers (which
  is safe, because the handler is idempotent). Producer group, consumer group,
  name server and topic all come from properties.
- [`SchedulingConfig`](../src/main/java/com/example/demo/config/SchedulingConfig.java) -
  turns on `@Scheduled` processing for the outbox relay. Gated on
  `outbox.relay.enabled` (default on) so the integration suite can switch the
  background poller off and drive relay passes deterministically.

### `exception/` - the error model

- [`ApiError`](../src/main/java/com/example/demo/exception/ApiError.java) -
  the single error shape every failure returns:
  `{timestamp, status, error, message, path}`.
- [`GlobalExceptionHandler`](../src/main/java/com/example/demo/exception/GlobalExceptionHandler.java) -
  one `@RestControllerAdvice` mapping every exception to that shape:

  | Exception | Status | Trigger |
  |---|---|---|
  | `SelfTransferException` | 400 | from == to |
  | `MethodArgumentNotValidException` | 400 | invalid request body (`@Valid`) |
  | `ConstraintViolationException` | 400 | invalid query param (`@Validated`) |
  | `MissingServletRequestParameterException` | 400 | missing required query param |
  | `MethodArgumentTypeMismatchException` | 400 | e.g. non-numeric transferId |
  | `HttpMessageNotReadableException` | 400 | malformed JSON body |
  | `UserNotFoundException`, `TransferNotFoundException` | 404 | unknown user / transfer |
  | `UserAlreadyExistsException` | 409 | duplicate userId |
  | `InsufficientFundsException` | 409 | debit guard hit 0 rows |
  | `DuplicateRequestException` | 409 | lost a concurrent same-requestId race (same payload) |
  | `CancellationNotAllowedException` | 409 | outside the 10-minute window / reversal target |
  | `IdempotencyConflictException` | 422 | requestId reused with a different payload |
  | anything implementing Spring's `ErrorResponse` | its own status | 405, unknown route 404, 406/415 - honored, not masked as 500 |
  | everything else | 500 | genuine bug: stack logged, no internals leaked |

  The `ErrorResponse` special case in the catch-all is the subtle part: Spring
  Boot 3 routes unknown URLs through `NoResourceFoundException`, which a naive
  `@ExceptionHandler(Exception.class)` would turn into a 500. The catch-all
  also preserves the headers the `ErrorResponse` mandates (e.g. `Allow` on a
  405), falls back to 500 for status codes outside the `HttpStatus` enum, and
  logs the stack for any 5xx it passes through.
- The seven domain exception classes are one-liners; the two with javadoc
  worth reading are
  [`CancellationNotAllowedException`](../src/main/java/com/example/demo/exception/CancellationNotAllowedException.java)
  (why already-cancelled is *not* an error) and
  [`IdempotencyConflictException`](../src/main/java/com/example/demo/exception/IdempotencyConflictException.java)
  (the Stripe-style 422 contract).

## Build file ([`pom.xml`](../pom.xml))

Spring Boot 3.5 parent, Java 21. Direct dependencies and why each exists:

- `spring-boot-starter-web` + `spring-boot-starter-validation` - REST layer
  and Bean Validation on the request records.
- `spring-boot-starter-jdbc` - `JdbcClient` (deliberately no JPA; see the
  repository section).
- `spring-boot-starter-data-redis` - `StringRedisTemplate` for the cache.
- `mysql-connector-j` (runtime scope) - the JDBC driver.
- `rocketmq-client` 5.3.2 - the raw Apache client, not the Spring Boot
  starter, so the publisher/consumer lifecycle stays explicit and gated by
  `rocketmq.enabled`. It transitively brings gRPC 1.53.0 (RocketMQ's own
  managed version); the app declares no gRPC itself.
- `spring-boot-starter-test` - JUnit 5, AssertJ, MockMvc et al.
- `org.testcontainers:junit-jupiter` + `org.testcontainers:mysql` (test scope,
  versions from the Boot BOM) - self-managed MySQL/Redis containers for the
  integration suite; the Redis side uses the plain `GenericContainer` from
  testcontainers core, so no extra module is needed.

Build customizations: the `maven-failsafe-plugin` execution that runs `*IT`
classes during `./mvnw verify` (surefire handles `*Test` units), plus the two
quality gates bound to `verify` - the `jacoco-maven-plugin` coverage check and
the `spotbugs-maven-plugin` static-analysis check (thresholds and how CI runs
them are documented in the README's Test and CI sections).

## Configuration ([`application.yaml`](../src/main/resources/application.yaml))

- `spring.datasource.*` - the compose MySQL (`taskdb`, `taskuser`), Hikari
  pool capped at 10.
- `spring.data.redis.*` - the compose Redis.
- `outbox.relay.*` - custom properties for the outbox relay: `enabled` (the
  scheduler gate; tests turn it off and drive the relay by hand),
  `poll-interval-ms` (1000), `batch-size` (100).
- `rocketmq.*` - custom properties (the app uses the raw `rocketmq-client`,
  not the Spring Boot starter): `enabled` (the publisher/consumer gate),
  `name-server`, `producer.group`, `consumer.group`, `topic.transfer`.
  `enabled` defaults to `true` here for live runs; tests force it to `false`.

## Dev stack ([`docker-compose.yaml`](../docker-compose.yaml) + [`broker.conf`](../broker.conf))

- **mysql** (8.0, port 3306) - seeds the schema by mounting `init.sql` into
  `/docker-entrypoint-initdb.d/` (runs only on a fresh volume); healthcheck via
  `mysqladmin ping`; data persisted in the `mysql_data` volume.
- **redis** (7, port 6379) - no auth, dev only; `redis_data` volume.
- **rocketmq-namesrv** (5.1.4, port 9876) - routing registry. Its healthcheck
  greps `/proc/net/tcp{,6}` for a LISTEN socket on 9876 (no shell or bash in
  the probe) because namesrv speaks the binary remoting protocol, not HTTP -
  an HTTP `curl` check reports `unhealthy` forever. The rationale and rejected
  alternatives live in the comment in `docker-compose.yaml`.
- **rocketmq-broker** (5.1.4, ports 10911/10909) - loads `broker.conf`, whose
  two load-bearing lines are `brokerIP1 = 127.0.0.1` (advertise a
  host-reachable address so the app and tests on the host can publish/consume;
  the address the broker registers with namesrv is what clients dial) and
  `timerWheelEnable = false` (the 5.x delayed-message timer store is unused
  here and its boot-time scan hangs for tens of minutes under emulated Docker).
  `broker.conf` is a single-file bind mount: after editing it, recreate the
  container (`docker compose up -d --force-recreate rocketmq-broker`); a plain
  restart keeps the pre-edit content because Docker for Mac binds by inode.
- **rocketmq-console** (port 8088 → container 8080) - web UI for topics and
  messages. Note: with `brokerIP1 = 127.0.0.1` the console (inside the docker
  network) cannot dial the broker for message queries - the trade-off favors
  host clients, which is what this project needs.

## Test suite - what each class proves

All integration tests extend
[`AbstractIntegrationTest`](../src/test/java/com/example/demo/support/AbstractIntegrationTest.java),
which boots the full app on a random port against a Testcontainers-managed
MySQL 8 (singleton container, started once per JVM, seeded with the same
repo-root `init.sql` the compose stack uses) and Redis, forces
`rocketmq.enabled=false`, and wipes all three tables plus the `balance:*` keys
before each test. Deletion order matters: reversal rows first, because of the
self-FK. Two environment notes live in the class itself: a static block pins
docker-java's `api.version=1.44` (Docker Engine 29+ rejects the bundled
client's default 1.32 handshake with HTTP 400), and `@DynamicPropertySource`
rewires the datasource and Redis properties to the containers' mapped ports.

Current pass/skip totals live in [PROGRESS.md](../PROGRESS.md) (unit via
surefire, `*IT` via failsafe).

| Class | Kind | Proves |
|---|---|---|
| [`TransferConcurrencyIT`](../src/test/java/com/example/demo/TransferConcurrencyIT.java) | IT | The centerpiece: under concurrent overspend and bidirectional storms, no lost updates, no negative balance, total money conserved. |
| [`TransferConservationStressIT`](../src/test/java/com/example/demo/TransferConservationStressIT.java) | IT | Complements the fixed-pair storms with a random-pair storm from a fixed-seed schedule: total conserved, no negative balance, COMPLETED ledger rows match 201 responses one-to-one (replaying them reproduces every final balance), concurrent identical retries apply exactly once, and the cached read path agrees with the DB. |
| [`TransferEndpointIT`](../src/test/java/com/example/demo/TransferEndpointIT.java) | IT | Happy path 201 + the 4xx contract (insufficient funds, unknown parties, self-transfer, non-positive amount). |
| [`TransferIdempotencyIT`](../src/test/java/com/example/demo/TransferIdempotencyIT.java) | IT | Sequential replay returns the original; payload mismatch is 422 and moves no money; scale-insensitive amount comparison; concurrent duplicates apply exactly once; a race loser classifies payload mismatch as 422 like the sequential path. |
| [`TransferCancelIT`](../src/test/java/com/example/demo/TransferCancelIT.java) | IT | Reversal restores balances; double-cancel idempotent; 409 when receiver can't cover, outside the window, or target is a reversal; 404 unknown. |
| [`TransferHistoryIT`](../src/test/java/com/example/demo/TransferHistoryIT.java) | IT | Newest-first ordering as sender or receiver, stable pagination, empty page, 400 on bad paging. |
| [`UserEndpointIT`](../src/test/java/com/example/demo/UserEndpointIT.java) | IT | Create/balance happy paths, 409 duplicate, 404 unknown, 400 negative initial balance. |
| [`BalanceCacheIT`](../src/test/java/com/example/demo/BalanceCacheIT.java) | IT | Cache-aside works: second read served from Redis without hitting MySQL; a transfer evicts. |
| [`AuditIdempotencyIT`](../src/test/java/com/example/demo/AuditIdempotencyIT.java) | IT | Redelivering the same event (completed or cancelled) to the handler writes exactly one audit row per event type - the consumer tolerates the relay's at-least-once duplicates (no broker needed). |
| [`OutboxAtomicityIT`](../src/test/java/com/example/demo/OutboxAtomicityIT.java) | IT | The outbox write is atomic with the business transaction: a committed transfer/cancel leaves exactly one unpublished row, a failed transfer leaves none, a rolled-back transaction takes its row with it. |
| [`OutboxRelayIT`](../src/test/java/com/example/demo/OutboxRelayIT.java) | IT | Against a spied sender (no broker): the relay publishes and marks each row exactly once; a failed publish defers the row with backoff (attempts incremented, not retried while deferred) and a later pass delivers it. |
| [`OutboxRelaySchedulingIT`](../src/test/java/com/example/demo/OutboxRelaySchedulingIT.java) | IT | The `@Scheduled` wiring: with `outbox.relay.enabled` on, the background poller drains a committed row without a manual relay pass. |
| [`ErrorModelIT`](../src/test/java/com/example/demo/ErrorModelIT.java) | IT | Malformed JSON (400), unsupported method (405) and unknown route (404) all come back in the `ApiError` shape. |
| [`BalanceCacheTest`](../src/test/java/com/example/demo/cache/BalanceCacheTest.java) | unit | The fail-open contract: get degrades to a miss, put/evict swallow Redis failures, hits parse, an unparseable entry is evicted and treated as a miss. |
| [`GlobalExceptionHandlerTest`](../src/test/java/com/example/demo/exception/GlobalExceptionHandlerTest.java) | unit | The catch-all keeps the `ApiError` shape for `ErrorResponse` exceptions: non-enum status codes fall back to 500, mandated headers (e.g. `Allow` on 405) survive. |
| [`TransferEventHandlerTest`](../src/test/java/com/example/demo/event/TransferEventHandlerTest.java) | unit | Handler records audit (cancelled events keyed on the original id) and evicts both balances. |
| [`TransferOutboxTest`](../src/test/java/com/example/demo/event/TransferOutboxTest.java) | unit | The appender writes the right event type + JSON payload, and a serialization failure fails the transaction instead of committing silently. |
| [`RocketMqOutboxSenderTest`](../src/test/java/com/example/demo/event/RocketMqOutboxSenderTest.java) | unit | The sender targets the configured topic with the event type as tag and the payload as body, and propagates broker failures to the relay. |
| [`DemoApplicationTests`](../src/test/java/com/example/demo/DemoApplicationTests.java) | unit | The Spring context wires up (smoke). |
| [`RocketMqSmokeIT`](../src/test/java/com/example/demo/RocketMqSmokeIT.java) | IT, opt-in | The true end-to-end broker path: a live transfer writes an outbox row, the relay publishes it through the compose broker, and the push consumer writes the `audit_log` row. Opt-in via `ROCKETMQ_SMOKE=true` (slow: first-run topic-route propagation); skipped in the default suite, where the pieces are covered by `OutboxRelayIT`, the handler unit test and `AuditIdempotencyIT`. |

## The five invariants to hold in your head

If you remember nothing else, remember these; every file above serves one:

1. **Money moves only inside one DB transaction**, and the debit is an atomic
   conditional update. No app-level check-then-write, no distributed lock.
2. **Locks are always taken in ascending userId order**, so opposing transfers
   cannot deadlock.
3. **An idempotency key applies at most once**, enforced by a UNIQUE
   constraint (not a lookup), replayed only for identical payloads.
4. **History is append-only**; cancel compensates with a reversal row, never
   an update-in-place of balances or a delete.
5. **Redis and RocketMQ are helpers, never authorities**: cache is fail-open
   and TTL-bounded, events commit with the transfer in the transactional
   outbox and are relayed at-least-once, the consumer is idempotent because
   duplicates are the price of never losing an event.
