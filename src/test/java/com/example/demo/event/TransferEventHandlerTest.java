package com.example.demo.event;

import com.example.demo.cache.BalanceCache;
import com.example.demo.repository.AuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TransferEventHandlerTest {

    @Test
    void handle_recordsAudit_andEvictsBothBalances() {
        var audit = mock(AuditRepository.class);
        var cache = mock(BalanceCache.class);
        var handler = new TransferEventHandler(audit, cache, new ObjectMapper());
        var event = new TransferCompletedEvent(42L, "alice", "bob", new BigDecimal("10"));

        handler.handle(event);

        verify(audit).recordOnce(eq("TransferCompleted"), eq(42L), anyString());
        verify(cache).evict("alice");
        verify(cache).evict("bob");
    }
}
