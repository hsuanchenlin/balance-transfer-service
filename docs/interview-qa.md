# Code walkthrough and interviewer Q&A

A guided tour of the codebase plus answers to the design questions this
assignment is meant to probe. File references are clickable paths. Part 1 is
deliberately a 5-minute map; for the exhaustive file-by-file version (every
class, schema column by column, every test class) see
[code-walkthrough.md](code-walkthrough.md).

## Part 1 - the 5-minute code map

### Layout (one responsibility per package)

| Package | Role | Files |
|---|---|---|
| `controller/` | HTTP in/out + input validation, no logic | `UserController`, `TransferController` |
| `service/` | business rules + transaction boundaries | `UserService`, `TransferService` |
| `repository/` | one SQL statement per method, `JdbcClient` | `AccountRepository`, `TransferRepository`, `AuditRepository` |
| `model/` | request/response records (immutable DTOs) | `TransferRequest`, `PageResponse`, ... |
| `exception/` | domain exceptions + one place mapping them to HTTP | `GlobalExceptionHandler`, `ApiError` |
| `cache/` | Redis read-path cache, fail-open | `BalanceCache` |
| `event/` | RocketMQ DTOs, publisher (real + no-op), consumer handler | `TransferEventPublisher`, `TransferEventHandler` |
| `config/` | RocketMQ producer/consumer beans, gated on `rocketmq.enabled` | `RocketMqConfig` |

Three tables (`init.sql`): `account` (mutable authoritative balance),
`transfer` (append-only history, also carries the idempotency key and the
cancel linkage), `audit_log` (written asynchronously by the MQ consumer).

### Life of a transfer (`POST /transfers`)

1. `TransferController.transfer` - bean validation rejects blank users,
   non-positive amounts, scale > 4 (`TransferRequest` annotations).
2. `TransferService.transfer`, inside one `@Transactional`:
   - reject self-transfer, reject unknown sender/receiver;
   - if a `requestId` is supplied and a transfer with it already exists,
     return that original result (idempotent replay);
   - move the money: conditional debit + credit, rows touched in ascending
     `userId` order (`moveInLockOrder`);
   - insert the `transfer` row; a `UNIQUE(request_id)` violation here means a
     concurrent duplicate won the race, so this attempt rolls back;
   - register an afterCommit hook: evict both balance cache entries, publish
     `TransferCompletedEvent`.
3. Commit releases the row locks. The hook runs only after a successful commit.
4. Asynchronously, the RocketMQ consumer (`RocketMqConfig` listener ->
   `TransferEventHandler`) writes an idempotent `audit_log` row and evicts the
   cache again.

Cancel (`POST /transfers/{id}/cancel`) is the same shape in reverse: a guarded
status flip, then a compensating movement receiver -> sender, then afterCommit
side-effects. History (`GET /transfers`) and balance (`GET /users/{id}/balance`)
are read-only paths.

## Part 2 - interviewer Q&A

### Q1. How do you guarantee the transfer is atomic and never loses money?

Both balance changes and the history insert happen in a single database
transaction (`@Transactional` on `TransferService.transfer`), so they commit or
roll back as one unit. The debit is an atomic conditional update:

```sql
UPDATE account SET balance = balance - :amount
WHERE user_id = :from AND balance >= :amount
```

MySQL/InnoDB takes a row lock to execute this, so check-and-decrement is one
indivisible step; 0 rows affected means insufficient funds and the whole
transaction rolls back. There is no window where money exists in both accounts
or in neither. `TransferConcurrencyIT` proves it: N concurrent transfers over
shared accounts, then assert no negative balance and that the sum of all
balances is unchanged (conservation invariant).

### Q2. What about concurrent transfers - lost updates?

`balance = balance - :amount` is a relative update evaluated inside the row
lock, not a read-modify-write in application memory, so two concurrent debits
serialize on the InnoDB lock and both apply. A stale-read overwrite is
impossible because the application never writes an absolute balance.

### Q3. How do you avoid deadlocks?

Deadlock scenario: A->B and B->A run concurrently and each grabs its sender row
first, then blocks on the other's row. The fix is a deterministic lock order:
`moveInLockOrder` always touches the two account rows in ascending `userId`
order, whichever direction the money flows. All transactions acquire locks in
the same global order, so a cycle cannot form. (InnoDB would detect and kill a
deadlock anyway, but prevention beats retry loops.)

### Q4. Why not a Redis distributed lock?

Because it adds a failure domain without adding correctness (ADR-0001). A Redis
lock has known failure modes: a lock expiring while its holder is paused (GC,
network) lets two holders proceed; Redlock's safety under partitions is
contested. Meanwhile the shared MySQL is already the synchronization point that
every instance must write through, and its row locks give a strictly stronger
guarantee for free. Multi-instance correctness needs no app-level coordination
at all: the conditional UPDATE is safe from any number of instances, and the
duplicate-request case is covered by the idempotency key, not by locking.

### Q5. Why not `SELECT ... FOR UPDATE` or an optimistic version column?

`FOR UPDATE` (read, check in Java, write) works but is two round-trips and
moves the invariant into application code; the conditional UPDATE keeps the
check and the write in one statement where it cannot be raced. An optimistic
version column causes retry storms exactly when it matters (hot accounts under
contention). The one place `FOR UPDATE` is used is cancel classification
(`TransferRepository.findByIdForUpdate`), where a locking read is needed to see
the latest committed status rather than the transaction's snapshot.

### Q6. How does idempotency work, exactly?

The client may send a `requestId`; it is stored on the transfer row under a
UNIQUE constraint. Three cases:

- **Sequential retry** (common): the service finds the existing row by
  `requestId`, verifies the retry carries the same payload (parties and
  amount, compared with `compareTo` so `100` and `100.00` match), and returns
  the original result without moving money.
- **Reused key, different payload**: rejected with 422
  (`IdempotencyConflictException`). A mismatched "retry" is a client bug;
  replaying the original result would silently answer a question the client
  did not ask. Same contract as Stripe's idempotency keys.
- **Concurrent duplicate** (race): both requests pass the lookup, both debit,
  but only one INSERT of the transfer row can succeed; the loser gets
  `DuplicateKeyException`, which rolls back its balance changes, and returns
  409. Net effect: at most one application of the transfer.

The database constraint, not a check-then-act, is the guarantee - it holds
across instances and across races. This is what makes retries safe in a
multi-instance deployment.

### Q7. What is Redis actually for, and what happens when it is stale or down?

Read path only: `GET balance` is cache-aside (`BalanceCache`, key
`balance:<userId>`, 5-minute TTL); a transfer evicts both parties' entries
after commit. Evict-not-update is deliberate: computing the new value in the
app would race with other writers, while an eviction forces the next read to
the authority. Eviction happens afterCommit so a concurrent read cannot
repopulate the cache with uncommitted data. Staleness is bounded by the TTL
(the classic cache-aside read-then-put race can leave one stale entry; it
self-heals within 5 minutes). Redis being down degrades performance, never
correctness: the cache is fail-open (every operation catches
`DataAccessException` and turns it into a miss/no-op, `BalanceCacheTest`), so
reads fall through to MySQL and committed transfers still return 201.

### Q8. What is RocketMQ for, and what if the broker is down?

Asynchronous side-effects that must not sit inside the money transaction:
`TransferCompletedEvent` / `TransferCancelledEvent` are published afterCommit;
the consumer writes an `audit_log` row and evicts caches. Publishing is
best-effort by design: the transfer is already committed, so a broker outage
logs a warning and the API call still succeeds
(`RocketMqTransferEventPublisher`). The gap - a crash after commit but before
publish loses the event - is the textbook case for a transactional outbox
(write the event into the DB transaction, relay it separately), documented as
the production evolution. Consumer redelivery is handled by idempotency:
`INSERT IGNORE` against `UNIQUE(event_type, transfer_id)` (`AuditRepository`),
and cache eviction is naturally idempotent.

### Q9. Walk me through cancel. Why "compensating reversal"?

Transfers are immediate and final on commit (the atomicity requirement rules
out a pending/settlement state), so undo must be a new compensating movement,
not a mutation of history (ADR-0002). In one transaction:

1. Guarded flip: `UPDATE transfer SET status='CANCELLED' WHERE id=:id AND
   status='COMPLETED' AND reversal_of IS NULL AND created_at > NOW() - INTERVAL
   10 MINUTE`. Encoding all preconditions in the WHERE clause makes the flip
   atomic and double-cancel naturally idempotent (0 rows = nothing to do).
2. If 0 rows: classify with a `FOR UPDATE` re-read - already CANCELLED returns
   200 (idempotent), still COMPLETED means out-of-window 409, missing means 404.
3. Otherwise reverse the money: conditional debit of the *receiver* (they may
   have spent it - 0 rows = 409, whole cancel rolls back, never a negative
   balance), credit the sender, same ascending lock order.
4. Append a linked reversal row (`reversal_of` = original id) - history stays
   append-only, and reversal rows are themselves not cancellable.

### Q10. Why raw SQL via JdbcClient instead of JPA?

Correctness here hinges on exact SQL semantics: a conditional UPDATE with rows
affected, deterministic lock acquisition order, `FOR UPDATE` reads, `INSERT
IGNORE`. An ORM hides all of those behind entity state management and flush
ordering that would have to be fought, and it invites the read-modify-write
pattern this design exists to avoid. With five tables' worth of statements,
explicit SQL is less total machinery ("proper use" of MySQL, per the brief).

### Q11. Why BigDecimal and DECIMAL(19,4)?

Binary floating point cannot represent decimal amounts exactly (0.1 + 0.2 !=
0.3) and accumulates rounding drift across arithmetic - unacceptable for money.
`DECIMAL(19,4)` stores exact decimal digits; `BigDecimal` mirrors it in Java.
Validation caps scale at 4 (`@Digits(integer = 15, fraction = 4)`) so nothing
is silently rounded at the DB boundary, and `chk_account_balance_non_negative`
is a last-line DB constraint behind the conditional update.

### Q12. How does this behave across multiple app instances?

The app is stateless; every instance talks to the same MySQL. Row locks
serialize concurrent debits from any instance (Q1), the UNIQUE idempotency key
resolves cross-instance duplicate requests (Q6), and cache eviction plus TTL
bound cross-instance staleness (Q7). No sticky sessions, no leader, no
distributed lock - scale out by adding instances until the DB is the
bottleneck, then evolve the DB tier (read replicas for history, partitioning as
the documented future work).

### Q13. What are the known limits and what would you build next for production?

- **Transactional outbox** for reliable event publish (Q8's crash window).
- **Double-entry ledger** (`ledger_entry`) as the authoritative record, with
  `account.balance` as a projection - better auditability and reconciliation.
- **Keyset pagination + UNION-based history query**: `from_user_id = :u OR
  to_user_id = :u` defeats single-column indexes at scale, and offset paging
  drifts under concurrent inserts.
- **AuthN/Z, rate limiting, observability** - out of scope for the brief.

### Q14. Why integration tests against real MySQL instead of H2/mocks?

The centerpiece claims are about InnoDB behavior: row-lock serialization,
conditional-update atomicity, lock ordering, `FOR UPDATE` read visibility. H2's
lock semantics differ, so a green H2 test proves nothing about the production
engine. Hence the failsafe suite (`*IT`, `AbstractIntegrationTest`) boots the
real app against the compose MySQL and asserts external behavior - HTTP codes,
resulting balances, conservation of total money - while a few genuine units
(cache fail-open, event handler, publisher serialization) are surefire unit
tests with mocks. Testcontainers was the intended provider; this machine's
Docker 29.x is incompatible with the bundled docker-java client, so the base
class points at compose (swap documented in its Javadoc).

### Q15. What error contract does the API promise?

One JSON shape for every failure: `{timestamp, status, error, message, path}`
(`ApiError`, produced only by `GlobalExceptionHandler`). Domain exceptions map
to meaningful codes: 400 validation/self-transfer/malformed body, 404 unknown
user/transfer/route, 409 for every conflict with current state (insufficient
funds, duplicate user, duplicate `requestId` race, cancel out-of-window,
receiver cannot cover the reversal), and 422 when a `requestId` is reused with
a different payload. A catch-all keeps even unexpected 500s and
framework-generated statuses (405, 406, 415) in the same shape (`ErrorModelIT`).
409 vs 400 is deliberate: the request is well-formed but conflicts with state,
so a retry after the state changes may succeed.
