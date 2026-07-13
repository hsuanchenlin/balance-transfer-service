package com.example.demo.event;

import com.example.demo.cache.BalanceCache;
import com.example.demo.repository.AuditRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Consumer-side handler for transfer events. Runs the async side-effects — an
 * idempotent audit record and cache invalidation. Safe to invoke on redelivery
 * (audit insert is idempotent; eviction is naturally so). The event-type tags
 * double as RocketMQ message tags so the consumer can route by kind.
 */
@Component
public class TransferEventHandler {

    public static final String COMPLETED_EVENT_TYPE = "TransferCompleted";
    public static final String CANCELLED_EVENT_TYPE = "TransferCancelled";

    private final AuditRepository audit;
    private final BalanceCache cache;
    private final ObjectMapper mapper;

    public TransferEventHandler(AuditRepository audit, BalanceCache cache, ObjectMapper mapper) {
        this.audit = audit;
        this.cache = cache;
        this.mapper = mapper;
    }

    public void handle(TransferCompletedEvent event) {
        audit.recordOnce(COMPLETED_EVENT_TYPE, event.transferId(), toJson(event));
        cache.evict(event.fromUserId());
        cache.evict(event.toUserId());
    }

    public void handleCancelled(TransferCancelledEvent event) {
        // Keyed on the original transfer id so a completed + a cancelled audit row
        // for the same transfer coexist (the UNIQUE key is event_type + transfer_id).
        audit.recordOnce(CANCELLED_EVENT_TYPE, event.transferId(), toJson(event));
        cache.evict(event.fromUserId());
        cache.evict(event.toUserId());
    }

    private String toJson(Object event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
