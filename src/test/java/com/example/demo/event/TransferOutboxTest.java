package com.example.demo.event;

import com.example.demo.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransferOutboxTest {

    @Test
    void appendCompleted_writesEventTypeAndJsonPayload() {
        var repo = mock(OutboxRepository.class);
        var outbox = new TransferOutbox(repo, new ObjectMapper());

        outbox.appendCompleted(new TransferCompletedEvent(7L, "a", "b", new BigDecimal("5")));

        verify(repo).append(eq("TransferCompleted"), contains("\"transferId\":7"));
    }

    @Test
    void appendCancelled_writesEventTypeAndJsonPayload() {
        var repo = mock(OutboxRepository.class);
        var outbox = new TransferOutbox(repo, new ObjectMapper());

        outbox.appendCancelled(
                new TransferCancelledEvent(7L, 8L, "a", "b", new BigDecimal("5")));

        verify(repo).append(eq("TransferCancelled"), contains("\"reversalId\":8"));
    }

    @Test
    void serializationFailure_failsTheTransaction_ratherThanCommittingSilently() throws Exception {
        var repo = mock(OutboxRepository.class);
        var mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any()))
                .thenThrow(mock(JsonProcessingException.class));
        var outbox = new TransferOutbox(repo, mapper);
        var event = new TransferCompletedEvent(7L, "a", "b", new BigDecimal("5"));

        assertThatThrownBy(() -> outbox.appendCompleted(event))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repo);
    }
}
