# 06 — RocketMQ events

**What to build:** Every completed transfer emits a `TransferCompleted` event to RocketMQ, and a consumer performs the asynchronous side-effects, keeping the hot write transaction limited to the balance updates. Demonstrates proper, decoupled use of the message bus.

**Blocked by:** 03, 05

**Status:** ready-for-agent

- [ ] A completed transfer publishes a `TransferCompleted` event with a custom, well-defined message DTO.
- [ ] A consumer handles the event to run async side-effects — an audit-log record and cache invalidation — idempotently (safe to redeliver).
- [ ] Producer and consumer handlers have unit tests; one end-to-end smoke test runs against RocketMQ (Testcontainers) if Docker is available.
- [ ] The write transaction itself does not block on message delivery.
