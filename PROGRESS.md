# Balance Transfer Service — Progress & Handoff

_Last updated: 2026-07-13. Resume point for a fresh Claude Code CLI session._

A RESTful balance-transfer service (Spring Boot 3 / Java 21, MySQL + Redis + RocketMQ), built from a homework skeleton with a spec → ADRs → tickets → TDD workflow.

## Status: tickets 01–06 done, 07–09 remaining

| # | Ticket | Status |
|---|--------|--------|
| 01 | Setup & schema | ✅ done |
| 02 | Create user & get balance | ✅ done |
| 03 | ⭐ Transfer core + concurrency proof | ✅ done |
| 04 | Idempotency key | ✅ done |
| 05 | Redis balance cache | ✅ done |
| 06 | RocketMQ events | ✅ done |
| 07 | Transfer history (`GET /transfers`) | ⬜ ready-for-agent |
| 08 | Cancel (compensating reversal) | ⬜ ready-for-agent |
| 09 | Submission docs (README/HELP/curl) | ⬜ ready-for-agent |

**Tests:** 21 pass + 1 documented skip via `mvn verify`.
**Git:** branch `balance-transfer-service`, 8 commits ahead of `main`, open as **PR #1** → https://github.com/hsuanchenlin/balance-transfer-service/pull/1

## How to run

```bash
docker compose up -d          # MySQL(3306) + Redis(6379) + RocketMQ; REQUIRED for tests
./mvnw verify                 # runs unit (surefire) + integration *IT (failsafe)
./mvnw spring-boot:run        # run the app (needs the stack up)
docker compose down           # stop the stack
```

MySQL: db `taskdb`, user `taskuser` / `taskpass` (root/`root`). Schema is in `init.sql` (auto-applied on a fresh MySQL volume; if the volume already exists, apply new tables manually with `docker exec -i mysql mysql -uroot -proot taskdb < ...`).

## Architecture

Endpoints (implemented): `POST /users`, `GET /users/{id}/balance`, `POST /transfers`.
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
- **Idempotency:** optional `requestId` under a `UNIQUE` constraint; sequential retry replays the original, concurrent duplicate rolls back → applies at most once.
- **Redis:** read-path cache only; invalidated in an `afterCommit` hook.
- **RocketMQ:** transfer publishes `TransferCompletedEvent` afterCommit (best-effort); consumer writes an idempotent `audit_log` row + invalidates cache.

## ⚠️ Environment gotchas (important)

1. **Testcontainers does NOT work here** — this box's Docker Engine 29.x is incompatible with the docker-java client bundled in the current Testcontainers (API handshake → HTTP 400). Integration tests therefore run against the **compose MySQL** (`AbstractIntegrationTest`), which cleans tables + `balance:*` Redis keys before each test. `docker compose up -d` must be running. To revisit Testcontainers later, swap the base class (documented in its Javadoc).
2. **RocketMQ from the host** — the compose broker advertises a container-internal address unreachable from host clients. `broker.conf` now sets `brokerIP1=127.0.0.1` so a **restarted** stack is host-reachable. Tests keep RocketMQ **off** via `rocketmq.enabled=false` (see `AbstractIntegrationTest` and `DemoApplicationTests`); the end-to-end `RocketMqSmokeIT` is `@Disabled` with manual-run instructions. The real app defaults `rocketmq.enabled=true`.

## How to continue (the workflow)

Work the frontier one ticket at a time, clearing context between tickets:

1. Read the ticket: `.scratch/balance-transfer-service/issues/07-transfer-history.md` (next up).
2. Use the mattpocock skills: `/implement` (drives it), `/tdd` for the red→green loop. Name tests `*IT` for integration (real MySQL, runs in `mvn verify`) or `*Test` for unit (surefire).
3. Write a failing test → minimal code → `./mvnw verify` green.
4. Flip the ticket's `Status:` to `done`, check its boxes.
5. Commit (`Ticket 0N: <title>` + `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`) and `git push` (extends PR #1).

### Remaining tickets
- **07 — Transfer history:** `GET /transfers?userId=&page=&size=`, offset paging, sender-or-receiver, `ORDER BY created_at DESC, id DESC`. Reads the `transfer` table.
- **08 — Cancel (compensating reversal):** `POST /transfers/{id}/cancel` — guarded status flip `... WHERE id=:id AND status='COMPLETED' AND created_at > NOW() - INTERVAL 10 MINUTE`, atomic reversal on the receiver, `409` if receiver can't cover, idempotent double-cancel, emits `TransferCancelled`. Follows ADR-0002. Blocked by 03+06 (both done).
- **09 — Submission docs:** README narrating the design rationale (the ADR story), `HELP.md` setup/data scripts, curl/Postman samples. Blocked by all others.
