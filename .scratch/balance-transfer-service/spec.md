# Spec: Balance Transfer Service

Status: ready-for-agent
Created: 2026-07-11
Feature: balance-transfer-service

## Problem Statement

A backend engineer candidate must build a RESTful balance-transfer service (Spring Boot + MySQL + Redis + RocketMQ) that lets users hold a balance and transfer credit between each other. The transfer must be atomic and concurrency-safe — money must never be lost, duplicated, or driven negative — even under concurrent requests and a multi-instance deployment. The service is judged on API correctness, error handling, "proper use of each technology," and (for this build) senior-level design signal.

## Solution

A Spring Boot 3 / Java 21 service exposing five REST endpoints (create user, get balance, transfer, list history, cancel). MySQL is the single source of truth and the correctness authority for balances, enforced by an atomic conditional `UPDATE` inside one DB transaction. Redis serves the read path (balance cache) and stores idempotency markers. RocketMQ carries asynchronous side-effects (history read-model updates, cache invalidation) so the hot write transaction stays minimal. Cancellation is a compensating reversal within a 10-minute window. The submission's centerpiece is a concurrency test proving the money-safety invariant.

## User Stories

1. As an API client, I want to create a user with an initial balance, so that the user can participate in transfers.
2. As an API client, I want user creation to reject a duplicate `userId`, so that identities stay unique.
3. As an API client, I want to create a user with a validated non-negative initial balance, so that no user starts in an invalid state.
4. As an API client, I want to read a user's current balance, so that I can display or act on it.
5. As an API client, I want balance reads served from cache when possible, so that read-heavy traffic doesn't overload the database.
6. As an API client, I want a `404` when I query the balance of an unknown user, so that I can distinguish "missing" from "zero".
7. As an API client, I want to transfer an amount from one user to another, so that credit moves between accounts.
8. As an API client, I want a transfer to atomically debit the sender and credit the receiver, so that money is never partially applied.
9. As an API client, I want a transfer to be rejected with `409` when the sender has insufficient funds, so that balances never go negative.
10. As an API client, I want a transfer to be rejected when sender or receiver does not exist, so that funds don't vanish into invalid accounts.
11. As an API client, I want a transfer amount to be validated as strictly positive with bounded scale, so that malformed money is rejected.
12. As an API client, I want a transfer from a user to themselves to be rejected, so that no-op/abuse transfers are blocked.
13. As an API client, I want each transfer recorded immutably in history, so that there is an audit trail.
14. As an API client, I want to supply an idempotency key on a transfer, so that a retried request applies the transfer at most once.
15. As an operator, I want concurrent transfers on the same account to serialize correctly, so that no update is lost under load.
16. As an operator, I want correctness to hold across multiple app instances, so that horizontal scaling doesn't require an app-level lock.
17. As an API client, I want to list transfers where a user is sender or receiver, so that I can see their full activity.
18. As an API client, I want transfer history paginated and sorted most-recent-first, so that large histories are navigable.
19. As an API client, I want to cancel a recent transfer within 10 minutes, so that mistakes can be undone.
20. As an API client, I want a cancel to post a compensating reversal (debit receiver, credit sender) atomically, so that the reversal is itself safe.
21. As an API client, I want a cancel rejected with `409` when the receiver has already spent the funds, so that reversals never drive the receiver negative.
22. As an API client, I want a cancel outside the 10-minute window rejected, so that stale transfers stay final.
23. As an API client, I want a double-cancel to be idempotent (one reversal), so that retries are safe.
24. As an operator, I want a `TransferCompleted`/`TransferCancelled` event emitted, so that history projection and cache invalidation happen asynchronously.
25. As an API client, I want consistent structured error responses with meaningful status codes, so that failures are machine- and human-readable.

## Implementation Decisions

**Scope & intent.** Build to a "senior-signal showcase" bar: correct + idempotent + concurrency-proven + clean tech separation, with design rationale documented in the README. Overshooting the stated 3 hours is acceptable.

**Money representation.** `BigDecimal` in Java ↔ `DECIMAL(19,4)` in MySQL. Validation rejects `amount <= 0` and `scale > 4`. No `double`/`float` anywhere.

**Concurrency — DB as sole authority.** The transfer runs in one `@Transactional` DB transaction using an **atomic conditional update**:
`UPDATE account SET balance = balance - :amt WHERE user_id = :from AND balance >= :amt` (0 rows affected ⇒ insufficient funds ⇒ roll back), then the credit to the receiver. InnoDB row locks provide correctness across any number of app instances — no version column, no `SELECT … FOR UPDATE`, and no Redis lock in the write path. **Deadlocks are prevented by applying the two account updates in a deterministic order sorted by `user_id`.** Rationale (for README): a Redis distributed lock is weaker than the DB guarantee (Redlock expiry/pause failure modes) and adds a failure domain for no correctness gain; the shared DB is already the cross-instance synchronization point.

**Idempotency.** Transfers accept a client-supplied idempotency key (`requestId`). Uniqueness is enforced by a `UNIQUE` constraint in MySQL (a Redis marker may front it as a fast-path); a duplicate key returns the original result / `409` rather than re-applying. This — not a lock — is what makes the multi-instance duplicate-request case safe.

**Redis role.** Read path only: cache-aside/write-through for `GET balance`; store idempotency markers. Cache is invalidated on transfer/cancel (synchronously on the authoritative write and/or via the MQ consumer). **No write-path balance pre-check** (TOCTOU / false-negative risk; the DB conditional update is the guarantee).

**RocketMQ role.** The transfer/cancel transaction publishes a `TransferCompleted` / `TransferCancelled` event; a consumer performs non-authoritative side-effects (history read-model maintenance, cache invalidation). Keeps the hot DB transaction limited to the balance updates + the transfer row. Custom DTO/message format defined for the events.

**Cancel semantics.** Transfers are immediate (`status = COMPLETED` on commit); the spec's "reverse if not yet settled" is reinterpreted as a **compensating reversal** within 10 minutes (the pending/settled model is rejected because it would violate the atomic-immediate requirement). Cancel flips status with a guarded update — `UPDATE transfer SET status='CANCELLED' WHERE id=:id AND status='COMPLETED' AND created_at > NOW() - INTERVAL 10 MINUTE` (0 rows ⇒ already cancelled / too old / missing, making double-cancel idempotent) — and applies the reversal as an atomic conditional update on the receiver in the same transaction. If the receiver can't cover the reversal, the cancel is rejected with **`409`** rather than going negative.

**Persistence.** `JdbcClient` (Spring 6.1) on the existing `spring-boot-starter-jdbc`. Add dependencies: `mysql-connector-j`, `spring-boot-starter-web`, `spring-boot-starter-validation`. Explicit SQL chosen over ORM because correctness hinges on exact lock semantics.

**Ledger model.** Authoritative mutable `account.balance` column (required by the conditional-update design) + an **append-only** `transfer` history table (`id, from_user_id, to_user_id, amount, status, request_id, created_at`, plus a reversal linkage). Records are never deleted or rewritten; cancel posts a linked reversal + status flip. Full double-entry `ledger_entry` is documented as the production evolution, not built.

**Schema (MySQL, authored in `init.sql`).** `account(user_id PK, balance DECIMAL(19,4) NOT NULL CHECK >= 0, created_at, updated_at)`; `transfer(id PK, from_user_id, to_user_id, amount DECIMAL(19,4), status ENUM(COMPLETED,CANCELLED), request_id UNIQUE, reversal_of nullable, created_at)` with indexes on `from_user_id`, `to_user_id`, `created_at`.

**API contracts.**
- `POST /users` → `201`; body `{userId, initialBalance}`; `409` on duplicate.
- `GET /users/{userId}/balance` → `200 {userId, balance}`; `404` if unknown.
- `POST /transfers` → `201 {transferId, status}`; body `{fromUserId, toUserId, amount, requestId}`; `409` insufficient funds / duplicate key; `404` unknown user; `400` invalid amount / self-transfer.
- `GET /transfers?userId=&page=&size=` → `200` page of transfers where user is sender or receiver, `ORDER BY created_at DESC, id DESC`.
- `POST /transfers/{transferId}/cancel` → `200`; `409` if too late or receiver can't cover; `404` if unknown.

**Error model.** `@RestControllerAdvice` maps domain exceptions to a consistent JSON body `{timestamp, status, error, message, path}`. Codes: `400` validation, `404` unknown user/transfer, `409` insufficient funds / cancel-too-late / duplicate-idempotency-key, `201` create/transfer, `200` reads.

**Module layout.** `controller` (REST + DTO validation), `service` (business logic, transactions, orchestration), `repository` (JdbcClient SQL), `model` (entities/DTOs), `config` (Redis, RocketMQ beans), `mq` (event producers/consumers).

## Testing Decisions

A good test asserts **external behavior** (HTTP result, resulting balances, emitted events, history contents), not implementation details. Preferred seams, highest first:

- **Service layer (primary seam)** — unit tests for validation, insufficient-funds, self-transfer, cancel-window, "receiver can't cover → 409", and the idempotency branch, with data access faked.
- **HTTP endpoint** — integration tests over real MySQL + Redis via **Testcontainers**, exercising each endpoint end-to-end (real SQL, real cache behavior). H2 is explicitly rejected — its lock semantics differ from InnoDB and can't validate concurrency.
- **Repository/SQL** — the concurrency proof lives here.

Test set:
1. **⭐ Concurrency test (centerpiece):** N threads issue concurrent transfers against shared accounts; assert no lost updates, no negative balance, and conservation of total credit (sum of balances invariant). This proves the atomic conditional update.
2. **Idempotency test:** same `requestId` twice ⇒ single applied effect.
3. **Service unit tests:** all branch/edge logic above.
4. **Endpoint integration tests:** happy path + each error code, via Testcontainers.
5. **RocketMQ:** consumer/producer **handlers tested as units**; one Testcontainers smoke test end-to-end if time permits.

**Prerequisite:** Testcontainers requires the Docker daemon running (Docker is installed locally but was not running at spec time) — or point integration tests at the provided `docker compose up -d` stack.

## Out of Scope

- Authentication / authorization (who may transfer or cancel).
- Multi-currency (single implicit currency; `DECIMAL(19,4)` leaves room).
- Full double-entry `ledger_entry` table (documented as evolution, not built).
- Redis distributed lock / write-path pre-check (deliberately rejected).
- Pending/asynchronous settlement state machine (rejected — violates atomic-immediate transfer).
- Keyset/cursor pagination (offset used now; keyset noted as scale evolution).
- Horizontal DB scaling (sharding/partitioning) — noted as future work only.

## Further Notes

- README should explicitly narrate the load-bearing design rationales: DB-as-authority vs Redis lock (with the Redlock failure mode), idempotency-not-lock for the multi-instance duplicate case, Redis on the read path only, MQ for async side-effects, and cancel-as-compensation with the 409 edge.
- Missing skeleton dependencies (`mysql-connector-j`, `spring-boot-starter-web`, `spring-boot-starter-validation`) must be added first; `init.sql` and `application.yaml` (mysql/redis/rocketmq connection blocks) must be filled in.
- The concurrency test is the single most persuasive artifact — build it early and keep it green.
