package com.example.demo;

import com.example.demo.event.TransferCancelledEvent;
import com.example.demo.event.TransferCompletedEvent;
import com.example.demo.event.TransferEventHandler;
import com.example.demo.repository.AuditRepository;
import com.example.demo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the consumer handler is idempotent on redelivery, against real MySQL (no broker needed). */
class AuditIdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    private TransferEventHandler handler;

    @Autowired
    private AuditRepository audit;

    @Test
    void redeliveredEvent_isRecordedExactlyOnce() {
        var event = new TransferCompletedEvent(555L, "u1", "u2", new BigDecimal("10"));

        handler.handle(event);
        handler.handle(event); // simulate RocketMQ redelivery

        assertThat(audit.countByTransferId(555L)).isEqualTo(1);
    }

    @Test
    void redeliveredCancelledEvent_isRecordedExactlyOnce_alongsideTheCompletedRow() {
        var completed = new TransferCompletedEvent(556L, "u1", "u2", new BigDecimal("10"));
        var cancelled = new TransferCancelledEvent(556L, 557L, "u1", "u2", new BigDecimal("10"));

        handler.handle(completed);
        handler.handleCancelled(cancelled);
        handler.handleCancelled(cancelled); // simulate at-least-once duplicate from the relay

        // One row per event type: the duplicate collapses on UNIQUE(event_type, transfer_id).
        assertThat(audit.countByTransferId(556L)).isEqualTo(2);
    }
}
