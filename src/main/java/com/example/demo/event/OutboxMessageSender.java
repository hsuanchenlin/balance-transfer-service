package com.example.demo.event;

/**
 * Broker-facing seam of the outbox relay: delivers one serialized event. A thrown
 * exception means "not delivered" and makes the relay defer the row for retry.
 */
public interface OutboxMessageSender {

    /**
     * @param eventType routing tag ({@code TransferCompleted} / {@code TransferCancelled})
     * @param payload   message body, the JSON stored in the outbox row
     */
    void send(String eventType, String payload) throws Exception;
}
