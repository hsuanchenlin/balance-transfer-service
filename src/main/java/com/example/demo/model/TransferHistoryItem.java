package com.example.demo.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row in a user's transfer history. {@code reversalOf} is non-null only for
 * the compensating reversal appended when a transfer is cancelled (ticket 08),
 * pointing back at the original transfer's id.
 */
public record TransferHistoryItem(
        long id,
        String fromUserId,
        String toUserId,
        BigDecimal amount,
        String status,
        Long reversalOf,
        Instant createdAt) {
}
