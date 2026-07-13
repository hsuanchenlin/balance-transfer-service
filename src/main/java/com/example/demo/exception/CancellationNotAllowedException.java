package com.example.demo.exception;

/**
 * A transfer exists but can no longer be cancelled — it is outside the 10-minute
 * window (ADR-0002). Maps to 409. Note an <em>already cancelled</em> transfer is
 * not an error: a double-cancel replays idempotently.
 */
public class CancellationNotAllowedException extends RuntimeException {
    public CancellationNotAllowedException(long transferId) {
        super("Transfer can no longer be cancelled: " + transferId);
    }
}
