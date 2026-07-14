package com.example.demo.event;

import com.example.demo.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Request-path half of the transactional outbox: serializes a transfer event and
 * appends it to {@code outbox_event}. Must be invoked inside the business
 * transaction so the event commits atomically with the transfer/cancel it
 * describes; nothing here talks to the broker - delivery is {@link OutboxRelay}'s
 * job, after commit, with retries.
 */
@Component
public class TransferOutbox {

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public TransferOutbox(OutboxRepository outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    public void appendCompleted(TransferCompletedEvent event) {
        outbox.append(TransferEventHandler.COMPLETED_EVENT_TYPE, toJson(event));
    }

    public void appendCancelled(TransferCancelledEvent event) {
        outbox.append(TransferEventHandler.CANCELLED_EVENT_TYPE, toJson(event));
    }

    private String toJson(Object event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Fail the surrounding transaction: committing a transfer whose audit
            // event can never be delivered would silently break at-least-once.
            throw new IllegalStateException(
                    "Could not serialize " + event.getClass().getSimpleName() + " for the outbox", e);
        }
    }
}
