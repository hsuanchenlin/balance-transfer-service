# 04 — Idempotency key on transfers

**What to build:** A transfer carries a client-supplied idempotency key so that a retried request — including one that lands on a different app instance — applies the transfer at most once. This is what makes the multi-instance duplicate-request case safe (per ADR-0001, idempotency not a lock).

**Blocked by:** 03

**Status:** done

- [x] `POST /transfers` accepts a `requestId`; a UNIQUE constraint enforces at-most-once application.
- [x] A duplicate `requestId` returns the original transfer's outcome (sequential retry replays the original id; a concurrent duplicate is rolled back and rejected `409`) without applying a second transfer.
- [x] Test: firing the same `requestId` twice sequentially (replays original id, balance moves once) and 16× concurrently (exactly one balance change).
