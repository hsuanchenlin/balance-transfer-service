# Balance Transfer Service — Progress & Handoff

_Last updated: 2026-07-14. Resume point for a fresh Claude Code CLI session._

A RESTful balance-transfer service (Spring Boot 3 / Java 21, MySQL + Redis + RocketMQ), built from a homework skeleton with a spec → ADRs → tickets → TDD workflow.

## Status: all tickets 01–09 done ✅ (assignment feature-complete)

| # | Ticket | Status |
|---|--------|--------|
| 01 | Setup & schema | ✅ done |
| 02 | Create user & get balance | ✅ done |
| 03 | ⭐ Transfer core + concurrency proof | ✅ done |
| 04 | Idempotency key | ✅ done |
| 05 | Redis balance cache | ✅ done |
| 06 | RocketMQ events | ✅ done |
| 07 | Transfer history (`GET /transfers`) | ✅ done |
| 08 | Cancel (compensating reversal) | ✅ done |
| 09 | Submission docs (README/HELP/curl) | ✅ done |

All five assignment endpoints are implemented; README/HELP/curl submission docs are written.

**Tests:** 42 pass + 1 documented skip via `mvn verify` (surefire 8 unit + failsafe 35 IT).
**Git:** **PR #1** (https://github.com/hsuanchenlin/balance-transfer-service/pull/1) was **merged into `main`** on 2026-07-13 (merge commit `b986e50`). All work from branch `balance-transfer-service` (tickets 01–09, including history + cancel + submission docs and the `markCancelled` reversal-row regression fix) has fully landed on `main`; start new work on a fresh branch/PR off `main`.

## How to run

```bash
docker compose up -d          # MySQL(3306) + Redis(6379) + RocketMQ; REQUIRED for tests
./mvnw verify                 # runs unit (surefire) + integration *IT (failsafe)
./mvnw spring-boot:run        # run the app (needs the stack up)
docker compose down           # stop the stack
```

MySQL: db `taskdb`, user `taskuser` / `taskpass` (root/`root`). Schema is in `init.sql` (auto-applied on a fresh MySQL volume; if the volume already exists, apply new tables manually with `docker exec -i mysql mysql -uroot -proot taskdb < ...`).

## Architecture

Endpoints (all implemented): `POST /users`, `GET /users/{id}/balance`, `POST /transfers`, `GET /transfers?userId=&page=&size=`, `POST /transfers/{id}/cancel`.
Package layout under `src/main/java/com/example/demo/`:

- `controller/` — REST + DTO validation (`UserController`, `TransferController`)
- `service/` — business logic + `@Transactional` (`UserService`, `TransferService`)
- `repository/` — `JdbcClient` SQL (`AccountRepository`, `TransferRepository`, `AuditRepository`)
- `model/` — request/response records
- `exception/` — domain exceptions + `GlobalExceptionHandler` (`@RestControllerAdvice`, `ApiError` body `{timestamp,status,error,message,path}`)
- `cache/` — `BalanceCache` (Redis cache-aside, keys `balance:<userId>`, 5-min TTL)
- `event/` — RocketMQ DTO, publisher (real + no-op), consumer handler
- `config/` — `RocketMqConfig` (producer + push consumer, gated on `rocketmq.enabled`)

Design canon (READ THESE before changing behavior):
- `CONTEXT.md` — domain glossary (ubiquitous language)
- `docs/adr/0001-db-as-correctness-authority.md` — DB is the money authority; no Redis lock; idempotency-key (not a lock) for multi-instance
- `docs/adr/0002-cancel-as-compensating-reversal.md` — cancel = compensating reversal, 409 when receiver can't cover
- `docs/agents/` — issue-tracker (local markdown under `.scratch/`), domain-doc rules, triage labels
- `.scratch/balance-transfer-service/spec.md` — full spec; `issues/NN-*.md` — the 9 tickets

### Load-bearing decisions already implemented
- **Money:** `BigDecimal` ↔ `DECIMAL(19,4)`.
- **Transfer safety (ADR-0001):** one `@Transactional` doing an atomic conditional debit `UPDATE account SET balance = balance - :amt WHERE user_id = :from AND balance >= :amt` (0 rows → `409`), with the two account rows touched in **ascending-userId order** to avoid deadlocks. Proven by `TransferConcurrencyIT`.
- **Idempotency:** optional `requestId` under a `UNIQUE` constraint; sequential retry with the same payload replays the original, a reused key with a different payload is rejected with `422`, concurrent duplicate rolls back → applies at most once.
- **Redis:** read-path cache only; invalidated in an `afterCommit` hook.
- **RocketMQ:** transfer publishes `TransferCompletedEvent` afterCommit (best-effort); consumer writes an idempotent `audit_log` row + invalidates cache.

## ⚠️ Environment gotchas (important)

1. **Testcontainers does NOT work here** — this box's Docker Engine 29.x is incompatible with the docker-java client bundled in the current Testcontainers (API handshake → HTTP 400). Integration tests therefore run against the **compose MySQL** (`AbstractIntegrationTest`), which cleans tables + `balance:*` Redis keys before each test. `docker compose up -d` must be running. To revisit Testcontainers later, swap the base class (documented in its Javadoc).
2. **RocketMQ from the host** — the compose broker advertises a container-internal address unreachable from host clients. `broker.conf` now sets `brokerIP1=127.0.0.1` so a **restarted** stack is host-reachable. Tests keep RocketMQ **off** via `rocketmq.enabled=false` (see `AbstractIntegrationTest` and `DemoApplicationTests`); the end-to-end `RocketMqSmokeIT` is `@Disabled` with manual-run instructions. The real app defaults `rocketmq.enabled=true`.

## What was delivered for 07–09

- **07 — Transfer history:** `GET /transfers?userId=&page=&size=` — offset paging, sender-or-receiver, `ORDER BY created_at DESC, id DESC`, `PageResponse` wrapper with `totalElements`. Bounds validated (`page≥0`, `1≤size≤100`) → `400`. `TransferHistoryIT`.
- **08 — Cancel (compensating reversal):** `POST /transfers/{id}/cancel` — guarded 10-min status flip, atomic receiver-debit/sender-credit in ascending-userId order, `409` if receiver can't cover, idempotent double-cancel (classified via a `FOR UPDATE` re-read), `404` unknown, appends a linked `reversal_of` row, emits `TransferCancelledEvent`. Follows ADR-0002. `TransferCancelIT`.
- **09 — Submission docs:** `README.md` (design-rationale narrative + API + run), `HELP.md` (setup/data scripts + gotchas), `scripts/curl-samples.sh` (runnable end-to-end walkthrough of all 5 endpoints incl. error cases). Verified live against a running app.
- **Bug caught during E2E verify:** history JSON showed `reversalOf: 0` instead of `null` — `rs.wasNull()` was read after a later column. Fixed in `TransferRepository.mapItem`; `TransferCancelIT` now asserts the field through the REST/Jackson path.

## Post-submission hardening (senior review pass)

A staff-level review of the whole codebase lives in `.scratch/balance-transfer-service/senior-review.md` (prioritized findings + status). Delivered so far:

- **Fail-open cache:** `BalanceCache` now catches `DataAccessException` on get/put/evict - a Redis outage degrades to DB reads and can no longer 500 a committed transfer via the afterCommit eviction (`BalanceCacheTest`).
- **Consistent error model:** malformed JSON, unknown routes, unsupported methods, and unexpected 500s now all return the `ApiError` shape via new `GlobalExceptionHandler` handlers (`ErrorModelIT`).
- **Defensive credit:** `TransferService` throws if a credit touches 0 rows instead of silently dropping money (unreachable today, invariant for future refactors).
- **Idempotency payload validation:** replaying a `requestId` with different parties or amount is rejected with `422` (`IdempotencyConflictException`) instead of silently returning the original result - Stripe's idempotency-key contract (`TransferIdempotencyIT`).
- **Config symmetry:** the RocketMQ consumer group now comes from `rocketmq.consumer.group` in `application.yaml` instead of a hardcoded string, mirroring the producer group.
- **Documented limits:** new README section "Known limits and scale evolutions" (history OR-query vs UNION ALL + keyset, offset-paging drift, cache-aside stale-read window, best-effort publish vs outbox), closing the last review-backlog items.
- **Interview prep:** `docs/interview-qa.md` - code walkthrough + answers to the 15 design questions an interviewer would ask.
- **Full comprehension guide:** `docs/code-walkthrough.md` - exhaustive file-by-file walkthrough (every class, schema column by column, config keys, and what each test class proves), for understanding every single piece of the code.
- **Postman collection:** `scripts/balance-transfer.postman_collection.json` - the curl walkthrough as a runnable collection with per-request assertions (21 requests, 30 assertions); verified green with `npx newman run` against the live app.

## To continue (workflow for future changes)

1. Test-first: name tests `*IT` for integration (real MySQL, runs in `mvn verify`) or `*Test` for unit (surefire).
2. `docker compose up -d`, then failing test → minimal code → `./mvnw verify` green.
3. Commit (`Ticket 0N: <title>` — do **not** add an agent co-author per repo convention). PR #1 is merged, so start future work on a **new branch off `main`** and open a new PR; do not reuse `balance-transfer-service`.

### Optional follow-ups (no pending assignment work)
The assignment is complete and merged; nothing is required. If someone wants to polish further:
- Resolving the Testcontainers gotcha (env gotcha 1 above) so ITs no longer need the compose MySQL.
- Un-disabling the RocketMQ smoke test once the broker is host-reachable (env gotcha 2 above).
