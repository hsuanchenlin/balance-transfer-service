# Senior-staff code review - findings and optimization backlog

Status: in-progress (worked through across gnhf iterations)
Created: 2026-07-13
Feature: balance-transfer-service

Scope reviewed: all of `src/main/java`, `init.sql`, `application.yaml`, test suite,
docs (README/HELP/ADRs/spec). Overall verdict: the load-bearing design is correct
and well-argued (ADR-0001 atomic conditional update + deterministic lock order,
ADR-0002 compensating reversal, idempotency via UNIQUE key). The findings below
are robustness/consistency/performance refinements, prioritized.

## P1 - fix now

1. **Redis cache is not fail-open.** `BalanceCache.get/put/evict` let
   `DataAccessException` propagate. Consequences when Redis is down or flaky:
   - `GET /users/{id}/balance` returns 500 even though MySQL (the authority) is fine.
   - A transfer/cancel that has already COMMITTED returns 500, because the
     afterCommit hook evicts the cache before publishing; the client sees an error
     for money that moved. The RocketMQ publish is also skipped (evict throws first).
   Fix: catch `DataAccessException` inside `BalanceCache`, log a warning, and degrade
   (miss on get, no-op on put/evict). TTL already bounds staleness from a missed
   eviction (documented self-heal). Verify with a unit test using a mocked
   `StringRedisTemplate`.
   -> Status: FIXED in iteration 1 (`BalanceCacheTest`).

## P2 - next iterations (each is one small, testable unit)

2. **Error-model consistency gaps.** Malformed JSON (`HttpMessageNotReadableException`)
   and any unhandled exception (500) fall through to Spring's default error body, not
   the documented `ApiError` shape `{timestamp,status,error,message,path}`. Add a
   handler for unreadable bodies (400) and a catch-all (500, generic message, log the
   stack). Test: POST invalid JSON to `/transfers`, assert ApiError shape.
   -> Status: FIXED in iteration 1 (`ErrorModelIT`).

3. **Defensive credit check.** `AccountRepository.credit()` returns rows-affected but
   `TransferService.moveInLockOrder` ignores it. Today a 0-row credit is unreachable
   (exists-check upfront, FKs on transfer, no account deletion), but the money path
   should be self-defending: `if (rows == 0) throw new IllegalStateException(...)`.
   Cheap invariant, protects future refactors.
   -> Status: FIXED in iteration 1 (creditOrThrow in TransferService).

4. **Idempotency replay does not validate the payload.** Reusing a `requestId` with a
   *different* amount/parties silently returns the original transfer's result. Industry
   practice (e.g. Stripe) rejects the mismatch (409/422). Decide: implement the
   comparison on replay, or document the current contract in README. Either is
   defensible for the homework; silence is not.
   -> Status: FIXED in iteration 2 - replay now compares from/to/amount
   (compareTo, scale-insensitive) and rejects mismatches with 422
   (`IdempotencyConflictException`); three new tests in `TransferIdempotencyIT`.

5. **RocketMQ consumer group is hardcoded** (`"balance-transfer-consumer"` in
   `RocketMqConfig`) while the producer group comes from yaml. Move to
   `rocketmq.consumer.group` for symmetry.

6. **History query scalability.** `WHERE from_user_id = :u OR to_user_id = :u` defeats
   single-column indexes (index-merge at best); `countByUser` repeats the scan every
   page. Fine at homework scale; the senior move is either a `UNION ALL` rewrite over
   the two indexes or an explicit README note that keyset pagination + UNION is the
   scale evolution (spec already lists keyset as out of scope). Prefer the README note
   unless benchmarks justify the rewrite.

## P3 - accepted tradeoffs (document, don't change)

- **Cache-aside stale-write race**: `getBalance` reads DB then `put`s; a transfer
  committing between the two can leave a stale cache entry until the 5-min TTL.
  Known cache-aside limitation; TTL bounds it. Worth one README sentence.
- **Best-effort event publish**: a crash between commit and publish loses the event;
  the transactional-outbox evolution is already noted in code and README.
- **Offset pagination** drift under concurrent inserts; keyset noted as evolution.
- **`exists()` via COUNT(*)**: `SELECT 1 ... LIMIT 1` is marginally cheaper; not worth
  the churn.

## Roadmap for remaining objective work

- Iteration 2+: work through P2 items top-down, one per iteration, test-first.
- Comprehension deliverable: a code walkthrough + interviewer Q&A doc
  (`docs/interview-qa.md`) covering every design question a reviewer would ask:
  why no Redis lock, deadlock avoidance, idempotency races, cache consistency,
  cancel-as-compensation, why JdbcClient over JPA, testing strategy.
  -> Status: DONE in iteration 1 (`docs/interview-qa.md`).
