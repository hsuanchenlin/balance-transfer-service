package com.example.demo;

import com.example.demo.event.TransferCompletedEvent;
import com.example.demo.event.TransferOutbox;
import com.example.demo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the outbox write is atomic with the business transaction: a committed
 * transfer/cancel leaves exactly one pending {@code outbox_event} row, a failed
 * transfer leaves none, and a rolled-back transaction takes its outbox row with it.
 * Nothing publishes from the request path - rows start unpublished.
 */
class OutboxAtomicityIT extends AbstractIntegrationTest {

    @Autowired
    private TransferOutbox transferOutbox;

    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void seedUsers() {
        rest.postForEntity("/users", Map.of("userId", "ob-from", "initialBalance", 100), String.class);
        rest.postForEntity("/users", Map.of("userId", "ob-to", "initialBalance", 0), String.class);
    }

    @Test
    void completedTransfer_commitsExactlyOneUnpublishedOutboxRow() {
        var resp = rest.postForEntity("/transfers",
                Map.of("fromUserId", "ob-from", "toUserId", "ob-to", "amount", 25), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long transferId = ((Number) resp.getBody().get("transferId")).longValue();

        assertThat(outboxCount()).isEqualTo(1);
        Map<String, Object> row = jdbc.sql(
                        "SELECT event_type, payload, attempts, published_at FROM outbox_event")
                .query().singleRow();
        assertThat(row.get("event_type")).isEqualTo("TransferCompleted");
        assertThat((String) row.get("payload")).contains("\"transferId\":" + transferId);
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("published_at")).as("request path must not publish").isNull();
    }

    @Test
    void cancelledTransfer_appendsCancelledOutboxRow() {
        var resp = rest.postForEntity("/transfers",
                Map.of("fromUserId", "ob-from", "toUserId", "ob-to", "amount", 25), Map.class);
        long transferId = ((Number) resp.getBody().get("transferId")).longValue();

        var cancel = rest.postForEntity("/transfers/" + transferId + "/cancel", null, Map.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'TransferCancelled'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(2); // the completed row plus the cancelled row
    }

    @Test
    void failedTransfer_writesNoOutboxRow() {
        var resp = rest.postForEntity("/transfers",
                Map.of("fromUserId", "ob-from", "toUserId", "ob-to", "amount", 5000), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // insufficient funds

        assertThat(outboxCount()).isZero();
    }

    @Test
    void rolledBackTransaction_leavesNoOutboxRow() {
        var tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            transferOutbox.appendCompleted(
                    new TransferCompletedEvent(999L, "ob-from", "ob-to", new BigDecimal("1")));
            status.setRollbackOnly();
        });

        assertThat(outboxCount()).as("outbox append must join the surrounding transaction").isZero();
    }

    private int outboxCount() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_event").query(Integer.class).single();
    }
}
