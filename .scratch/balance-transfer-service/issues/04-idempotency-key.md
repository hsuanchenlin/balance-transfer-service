# 04 — Idempotency key on transfers

**What to build:** A transfer carries a client-supplied idempotency key so that a retried request — including one that lands on a different app instance — applies the transfer at most once. This is what makes the multi-instance duplicate-request case safe (per ADR-0001, idempotency not a lock).

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] `POST /transfers` accepts a `requestId`; a UNIQUE constraint enforces at-most-once application.
- [ ] A duplicate `requestId` returns the original transfer's outcome (or `409`) without applying a second transfer.
- [ ] Test: firing the same `requestId` twice (sequentially and concurrently) results in exactly one balance change.
