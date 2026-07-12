package com.example.demo.event;

import com.example.demo.cache.BalanceCache;
import com.example.demo.repository.AuditRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Consumer-side handler for {@link TransferCompletedEvent}. Runs the async
 * side-effects — an idempotent audit record and cache invalidation. Safe to
 * invoke on redelivery (audit insert is idempotent; eviction is naturally so).
 */
@Component
public class TransferEventHandler {

    static final String EVENT_TYPE = "TransferCompleted";

    private final AuditRepository audit;
    private final BalanceCache cache;
    private final ObjectMapper mapper;

    public TransferEventHandler(AuditRepository audit, BalanceCache cache, ObjectMapper mapper) {
        this.audit = audit;
        this.cache = cache;
        this.mapper = mapper;
    }

    public void handle(TransferCompletedEvent event) {
        String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            payload = null;
        }
        audit.recordOnce(EVENT_TYPE, event.transferId(), payload);
        cache.evict(event.fromUserId());
        cache.evict(event.toUserId());
    }
}
