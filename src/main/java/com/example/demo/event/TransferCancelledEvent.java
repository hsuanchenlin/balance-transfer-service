package com.example.demo.event;

import java.math.BigDecimal;

/**
 * Custom message DTO published to RocketMQ when a transfer is cancelled
 * (ticket 08). {@code transferId} is the original transfer; {@code reversalId}
 * is the compensating reversal appended in its place. The user ids are those of
 * the original transfer, so the consumer can invalidate both balance caches.
 */
public record TransferCancelledEvent(
        long transferId,
        long reversalId,
        String fromUserId,
        String toUserId,
        BigDecimal amount) {
}
