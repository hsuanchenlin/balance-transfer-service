package com.example.demo;

import com.example.demo.support.AbstractIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the scheduled wiring end to end: with {@code outbox.relay.enabled} back on
 * (the base class disables it), the background poller drains a committed outbox row
 * without any manual relay pass. RocketMQ stays off, so delivery goes through the
 * no-op sender - the scheduling is what is under test here.
 *
 * <p>{@link DirtiesContext} closes this scheduler-enabled context after the class:
 * left cached, its background poller would keep draining the shared database while
 * later test classes assert on unpublished rows.
 */
@TestPropertySource(properties = {"outbox.relay.enabled=true", "outbox.relay.poll-interval-ms=200"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxRelaySchedulingIT extends AbstractIntegrationTest {

    @Test
    void scheduledPollerPublishesCommittedRowsWithoutManualDrive() {
        rest.postForEntity("/users", Map.of("userId", "sch-from", "initialBalance", 100), String.class);
        rest.postForEntity("/users", Map.of("userId", "sch-to", "initialBalance", 0), String.class);
        var resp = rest.postForEntity("/transfers",
                Map.of("fromUserId", "sch-from", "toUserId", "sch-to", "amount", 10), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NOT NULL")
                        .query(Integer.class).single())
                        .isEqualTo(1));
    }
}
