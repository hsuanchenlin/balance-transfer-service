package com.example.demo.exception;

/**
 * A {@code requestId} was reused with a different payload (parties or amount)
 * than the transfer originally recorded under that key. Replaying the original
 * result would silently answer a question the client did not ask, so the
 * mismatch is rejected as 422 (same contract as Stripe's idempotency keys).
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String requestId) {
        super("requestId " + requestId + " was already used with a different payload");
    }
}
