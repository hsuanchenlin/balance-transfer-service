package com.example.demo.event;

/**
 * A pending row of the transactional outbox as seen by the relay: the event type
 * (doubles as the RocketMQ message tag), the serialized message body, and how many
 * publish attempts have already failed (drives the backoff on the next failure).
 */
public record OutboxEvent(long id, String eventType, String payload, int attempts) {
}
