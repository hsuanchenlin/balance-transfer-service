package com.example.demo;

import com.example.demo.event.OutboxMessageSender;
import com.example.demo.event.OutboxRelay;
import com.example.demo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Drives {@link OutboxRelay#relayOnce()} against a spied {@link OutboxMessageSender}
 * (no broker): the relay publishes each committed row exactly once, and a failed
 * publish defers the row with backoff until a later pass delivers it.
 */
class OutboxRelayIT extends AbstractIntegrationTest {

    @MockitoSpyBean
    private OutboxMessageSender sender;

    @Autowired
    private OutboxRelay relay;

    @BeforeEach
    void seedUsers() {
        rest.postForEntity("/users", Map.of("userId", "rl-from", "initialBalance", 100), String.class);
        rest.postForEntity("/users", Map.of("userId", "rl-to", "initialBalance", 0), String.class);
    }

    private void transfer(int amount) {
        var resp = rest.postForEntity("/transfers",
                Map.of("fromUserId", "rl-from", "toUserId", "rl-to", "amount", amount), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void relayPublishesAndMarksEachRowExactlyOnce() throws Exception {
        transfer(10);
        transfer(20);

        assertThat(relay.relayOnce()).isEqualTo(2);
        verify(sender, times(2)).send(anyString(), anyString());
        assertThat(unpublishedCount()).isZero();
        assertThat(publishedCount()).isEqualTo(2);

        // A second pass finds nothing due: no re-publish of already-published rows.
        assertThat(relay.relayOnce()).isZero();
        verify(sender, times(2)).send(anyString(), anyString());
    }

    @Test
    void failedPublish_defersRowWithBackoff_thenLaterPassDeliversIt() throws Exception {
        doThrow(new RuntimeException("broker down")).when(sender).send(anyString(), anyString());
        transfer(10);

        assertThat(relay.relayOnce()).isZero();
        Map<String, Object> row = jdbc.sql(
                        "SELECT attempts, published_at FROM outbox_event")
                .query().singleRow();
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("attempts")).isEqualTo(1);
        // Compare against the DB clock, not the JVM clock: the two can disagree
        // (container timezone), and the relay's due-check runs on NOW() anyway.
        assertThat(jdbc.sql("SELECT next_attempt_at > NOW() FROM outbox_event")
                .query(Boolean.class).single())
                .as("failed row is deferred into the future")
                .isTrue();

        // Within the backoff window the row is not due: the sender is not retried.
        assertThat(relay.relayOnce()).isZero();
        verify(sender, times(1)).send(anyString(), anyString());

        // Broker recovers; fast-forward past the backoff and the next pass delivers.
        doNothing().when(sender).send(anyString(), anyString());
        jdbc.sql("UPDATE outbox_event SET next_attempt_at = TIMESTAMPADD(HOUR, -1, NOW())").update();
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(unpublishedCount()).isZero();
        assertThat(publishedCount()).isEqualTo(1);
    }

    @Test
    void repeatedFailures_keepIncrementingAttempts() throws Exception {
        doThrow(new RuntimeException("broker down")).when(sender).send(anyString(), anyString());
        transfer(10);

        relay.relayOnce();
        jdbc.sql("UPDATE outbox_event SET next_attempt_at = TIMESTAMPADD(HOUR, -1, NOW())").update();
        relay.relayOnce();

        assertThat(jdbc.sql("SELECT attempts FROM outbox_event").query(Integer.class).single())
                .isEqualTo(2);
        assertThat(publishedCount()).isZero();
    }

    private int unpublishedCount() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL")
                .query(Integer.class).single();
    }

    private int publishedCount() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NOT NULL")
                .query(Integer.class).single();
    }
}
