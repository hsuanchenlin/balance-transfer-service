package com.example.demo;

import com.example.demo.repository.AuditRepository;
import com.example.demo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end RocketMQ smoke test: a completed transfer writes an outbox row, the
 * relay publishes the {@code TransferCompleted} event through the real compose
 * broker, and the push consumer writes the {@code audit_log} row.
 *
 * <p>Opt-in via {@code ROCKETMQ_SMOKE=true} because it needs the compose broker to
 * advertise a host-reachable address ({@code brokerIP1 = 127.0.0.1} in
 * {@code broker.conf} - note the broker container must have been (re)created after
 * that line was added; a single-file bind mount goes stale when the host file is
 * rewritten) and because first-run topic-route propagation makes it slow (up to
 * ~90s). The handler logic and its idempotency are covered deterministically by
 * {@code TransferEventHandlerTest} and {@code AuditIdempotencyIT}.
 *
 * <p>Run: {@code ROCKETMQ_SMOKE=true ./mvnw -Dit.test=RocketMqSmokeIT verify}
 */
@EnabledIfEnvironmentVariable(named = "ROCKETMQ_SMOKE", matches = "true",
        disabledReason = "Needs the host-reachable compose broker; opt in with ROCKETMQ_SMOKE=true")
// Re-enable both halves the base class switches off: the real broker publisher
// and the scheduled outbox relay that feeds it.
@TestPropertySource(properties = {"rocketmq.enabled=true", "outbox.relay.enabled=true"})
class RocketMqSmokeIT extends AbstractIntegrationTest {

    @Autowired
    private AuditRepository audit;

    private long transfer(int amount) {
        var resp = rest.postForEntity("/transfers",
                Map.of("fromUserId", "mq-from", "toUserId", "mq-to", "amount", amount), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) resp.getBody().get("transferId")).longValue();
    }

    @Test
    void transferPublishesEvent_consumerWritesAuditRow() throws InterruptedException {
        rest.postForEntity("/users", Map.of("userId", "mq-from", "initialBalance", 100), String.class);
        rest.postForEntity("/users", Map.of("userId", "mq-to", "initialBalance", 0), String.class);

        // The first publish auto-creates the topic, and the consumer only picks up the
        // new route on its ~30s poll; with CONSUME_FROM_LAST_OFFSET a message sent
        // before that pickup is skipped. Re-send every 10s until one lands, so the
        // test proves the pipeline without depending on route-propagation timing.
        long transferId = transfer(1);
        long deadline = System.currentTimeMillis() + 90_000;
        while (audit.countByTransferId(transferId) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10_000);
            if (audit.countByTransferId(transferId) == 0) {
                transferId = transfer(1);
            }
        }

        assertThat(audit.countByTransferId(transferId))
                .as("audit_log row written by the RocketMQ consumer for transfer %d", transferId)
                .isEqualTo(1);
    }
}
