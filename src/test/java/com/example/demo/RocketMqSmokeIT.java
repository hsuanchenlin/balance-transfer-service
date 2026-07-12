package com.example.demo;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * End-to-end RocketMQ smoke test. Disabled in the default suite because the compose
 * broker advertises a container-internal address that a host client can't reach.
 *
 * <p>To run it manually: ensure {@code broker.conf} has {@code brokerIP1 = 127.0.0.1},
 * (re)start the stack with {@code docker compose up -d}, run the app with
 * {@code rocketmq.enabled=true}, POST a transfer, and confirm a {@code TransferCompleted}
 * row appears in {@code audit_log}. The handler logic and its idempotency are already
 * covered deterministically by {@code TransferEventHandlerTest} and {@code AuditIdempotencyIT}.
 */
@Disabled("Needs a host-reachable RocketMQ broker (brokerIP1). See class Javadoc; covered by unit + AuditIdempotencyIT.")
class RocketMqSmokeIT {

    @Test
    void transferPublishesEvent_consumerWritesAuditRow() {
        // Documented manual verification only.
    }
}
