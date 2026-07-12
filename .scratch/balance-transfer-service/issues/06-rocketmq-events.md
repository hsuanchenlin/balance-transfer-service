# 06 — RocketMQ events

**What to build:** Every completed transfer emits a `TransferCompleted` event to RocketMQ, and a consumer performs the asynchronous side-effects, keeping the hot write transaction limited to the balance updates. Demonstrates proper, decoupled use of the message bus.

**Blocked by:** 03, 05

**Status:** done

- [x] A completed transfer publishes a `TransferCompletedEvent` (custom DTO) to RocketMQ via the raw client, gated on `rocketmq.enabled`.
- [x] A consumer handles the event to run async side-effects — an idempotent `audit_log` record (`INSERT IGNORE` on `UNIQUE(event_type, transfer_id)`) and cache invalidation. Safe to redeliver.
- [x] Producer and consumer handlers have unit tests (`RocketMqTransferEventPublisherTest`, `TransferEventHandlerTest`); redelivery idempotency proven against real MySQL (`AuditIdempotencyIT`).
- [x] Publish runs in an **afterCommit** hook (best-effort, errors swallowed), so the write transaction never blocks on message delivery.
- [~] End-to-end smoke test present but `@Disabled` — the compose broker advertises a container-internal address unreachable from the host. `broker.conf` now sets `brokerIP1=127.0.0.1` (+ `autoCreateTopicEnable`) so a restarted stack is host-reachable; instructions in `RocketMqSmokeIT`.
