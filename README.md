# Balance Transfer Service

A RESTful service for holding per-user credit and moving it between users **atomically**, built with Spring Boot 3 (Java 21) on MySQL, Redis, and RocketMQ.

The interesting part of this problem is not the CRUD - it is keeping money correct under concurrency and across app instances. This README leads with the reasoning behind those decisions; the two ADRs under [`docs/adr/`](docs/adr) are the long form.

---

## Design rationale (the load-bearing decisions)

### 1. MySQL is the single correctness authority - no distributed lock ([ADR-0001](docs/adr/0001-db-as-correctness-authority.md))

The service can run as several instances, which tempts an app-level Redis (Redlock) lock around each transfer. We rejected that. The debit is a single **atomic conditional update**:

```sql
UPDATE account SET balance = balance - :amt WHERE user_id = :from AND balance >= :amt
```

`0 rows affected` means insufficient funds - the guard and the write are one indivisible step, so a balance can never go negative and there is no check-then-act race. InnoDB's row lock already serializes every instance through the database, so more app instances do **not** threaten the invariant.

A Redis lock would be *weaker* than this: a GC pause or clock skew can let the lock's TTL expire while the holder still believes it holds it, admitting a second writer. That is a second failure domain for zero correctness gain. So Redis stays off the write path entirely.

The two account rows a transfer touches (debit + credit) are always locked in **ascending `userId` order**, so two opposing transfers (`A→B` and `B→A`) can never deadlock. Proven by `TransferConcurrencyIT`, which fires many concurrent overspends and asserts no money is lost or created.

### 2. Idempotency key, not a lock, for duplicate requests ([ADR-0001](docs/adr/0001-db-as-correctness-authority.md))

The multi-instance hazard that *does* remain is a retried/duplicated request. A client may send an optional `requestId`, stored under a `UNIQUE` constraint:

- **Sequential retry** with the same payload replays the original transfer's result (same `transferId`), applying the money move once.
- **Reused key, different payload** (other parties or amount) is rejected with `422` - a mismatched "retry" is a client bug, and replaying the original result would silently answer a question the client did not ask (same contract as Stripe's idempotency keys).
- **Concurrent duplicate** loses the unique-key race and is rolled back (`409`), so the transfer applies **at most once**.

This is a database invariant, not a lock with a timeout - it holds no matter how many instances race. See `TransferIdempotencyIT`.

### 3. Redis is a read-path cache only

Balance reads are cache-aside (`balance:<userId>`, 5-min TTL). The cache is **invalidated after the DB commit** (`afterCommit` hook), never before - so a concurrent reader can't repopulate it with a balance the transaction might roll back. The TTL means any missed invalidation self-heals. Redis is never consulted to decide whether a transfer may proceed. The cache is also **fail-open**: a Redis outage degrades to reading MySQL directly (and never fails an already-committed transfer) - performance may drop, correctness cannot.

### 4. RocketMQ carries async side-effects, best-effort

On commit, a transfer publishes a `TransferCompletedEvent` (and a cancel publishes `TransferCancelledEvent`). The consumer writes an **idempotent** `audit_log` row (`UNIQUE(event_type, transfer_id)` + `INSERT IGNORE`, safe on redelivery) and invalidates cache. Publishing is best-effort and happens *after* commit: a broker outage can never fail or roll back a committed transfer. A production system would upgrade this to a transactional outbox; that trade-off is called out in the code.

### 5. Cancel is a compensating reversal ([ADR-0002](docs/adr/0002-cancel-as-compensating-reversal.md))

Transfers settle immediately - there is no "pending" state to abort. So cancellation is reinterpreted as a **compensating reversal** within a 10-minute window, all in one transaction:

- A **guarded status flip** encodes the whole policy:
  ```sql
  UPDATE transfer SET status='CANCELLED'
   WHERE id=:id AND status='COMPLETED' AND created_at > NOW() - INTERVAL 10 MINUTE
  ```
  `0 rows` means already-cancelled, too-old, or missing - which makes a **double-cancel naturally idempotent** (it replays as `200`, never reverses twice).
- The reversal moves the amount **back from receiver to sender** as an atomic conditional debit. If the receiver has since spent the money and can't cover it, the cancel is rejected with `409` rather than driving them negative.
- History stays **append-only**: a linked reversal row (`reversal_of` → original id) is added; nothing is deleted or rewritten.

Covered by `TransferCancelIT`: happy reversal, too-late `409`, receiver-can't-cover `409`, idempotent double-cancel, unknown `404`.

---

## API

Base URL `http://localhost:8080`. Errors share one JSON shape: `{timestamp, status, error, message, path}`.

| Method | Path | Purpose | Success | Notable errors |
|--------|------|---------|---------|----------------|
| `POST` | `/users` | Create a user with an initial balance | `201` | `409` duplicate userId, `400` validation |
| `GET` | `/users/{userId}/balance` | Current balance (Redis-cached) | `200` | `404` unknown user |
| `POST` | `/transfers` | Atomic transfer; optional `requestId` for idempotency | `201` | `409` insufficient funds / duplicate requestId, `422` requestId reused with different payload, `404` unknown user, `400` self-transfer or bad amount |
| `GET` | `/transfers?userId=&page=&size=` | Paged history (sender or receiver), newest first | `200` | `400` bad paging / missing userId |
| `POST` | `/transfers/{transferId}/cancel` | Compensating reversal within 10 min | `200` | `409` too old / receiver can't cover, `404` unknown transfer |

### Request / response shapes

```jsonc
// POST /users            → 201 (empty body)
{ "userId": "user_001", "initialBalance": 1000 }

// GET /users/user_001/balance → 200
{ "userId": "user_001", "balance": 1000.0000 }

// POST /transfers        → 201
{ "fromUserId": "user_001", "toUserId": "user_002", "amount": 150, "requestId": "optional-uuid" }
{ "transferId": 1, "status": "COMPLETED" }

// GET /transfers?userId=user_001&page=0&size=20 → 200
{ "content": [ { "id": 1, "fromUserId": "user_001", "toUserId": "user_002",
                 "amount": 150.0000, "status": "COMPLETED", "reversalOf": null,
                 "createdAt": "2026-07-13T13:00:00Z" } ],
  "page": 0, "size": 20, "totalElements": 1 }

// POST /transfers/1/cancel → 200
{ "transferId": 1, "status": "CANCELLED" }
```

Paging defaults: `page=0`, `size=20`; bounds `page ≥ 0`, `1 ≤ size ≤ 100` (out-of-range → `400`).

Runnable `curl` samples for every endpoint, including the error cases, are in [`scripts/curl-samples.sh`](scripts/curl-samples.sh). The same walkthrough is available as a Postman collection with per-request assertions: [`scripts/balance-transfer.postman_collection.json`](scripts/balance-transfer.postman_collection.json) (import it, or `npx newman run scripts/balance-transfer.postman_collection.json`).

---

## Run it

```bash
docker compose up -d      # MySQL(3306) + Redis(6379) + RocketMQ(9876/10911) + console(8088)
./mvnw spring-boot:run    # app on :8080
```

Then run the walkthrough:

```bash
./scripts/curl-samples.sh
```

Full setup notes, data scripts, and the test story are in [HELP.md](HELP.md).

## Test

```bash
docker compose up -d      # the integration tests run against this stack
./mvnw verify             # unit (surefire) + integration *IT (failsafe)
```

## Architecture

Standard layered structure under `src/main/java/com/example/demo/`:

- `controller/` - REST endpoints + DTO validation
- `service/` - business logic, `@Transactional` boundaries (the correctness lives here)
- `repository/` - `JdbcClient` SQL (explicit SQL by choice, so the conditional-update guards are visible)
- `model/` - request/response records
- `cache/` - `BalanceCache` (Redis cache-aside)
- `event/` - RocketMQ DTOs, publisher (real + no-op), consumer handler
- `config/` - RocketMQ producer/consumer wiring
- `exception/` - domain exceptions + `GlobalExceptionHandler`

Ubiquitous language is fixed in [CONTEXT.md](CONTEXT.md); the schema is in [init.sql](init.sql). For an exhaustive file-by-file tour (every class, the schema column by column, and what each test proves), see [docs/code-walkthrough.md](docs/code-walkthrough.md).

## Tech-choice note

`JdbcClient` over JPA on purpose: the whole design rests on *exact* SQL - the conditional-update guards, lock ordering, and the guarded status flip. Explicit SQL keeps those front and center instead of behind an ORM. `SELECT … FOR UPDATE` would give equivalent correctness to the conditional update and is the main alternative (see ADR-0001).

## Known limits and scale evolutions

Deliberate trade-offs at homework scale, with the production evolution named for each:

- **History query**: `WHERE from_user_id = :u OR to_user_id = :u` defeats single-column indexes (MySQL manages an index merge at best), and the `COUNT(*)` for page metadata repeats that scan on every page. The evolution is a `UNION ALL` over the two indexed halves plus keyset pagination; the spec lists keyset as out of scope, so the OR-query stays for readability.
- **Offset pagination drift**: a row inserted while a client pages can shift entries between pages. Keyset pagination (`WHERE (created_at, id) < (:cursor...)`) is the same evolution as above.
- **Cache-aside stale-read race**: `getBalance` reads MySQL then `put`s into Redis; a transfer committing between those two steps can leave a stale cached balance until the next eviction or the 5-minute TTL. This is the classic cache-aside window; the TTL bounds it, and no decision ever reads the cache.
- **Best-effort event publish**: a crash between the DB commit and the RocketMQ send loses that event (audit log misses one row). The evolution is a transactional outbox, called out in section 4 and in the publisher code.
