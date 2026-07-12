package com.example.demo.event;

import java.math.BigDecimal;

/** Custom message DTO published to RocketMQ when a transfer completes. */
public record TransferCompletedEvent(
        long transferId,
        String fromUserId,
        String toUserId,
        BigDecimal amount) {
}
