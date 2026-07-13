# Cancellation is a compensating reversal, rejected with 409 when the receiver can't cover it

The spec says a transfer may be cancelled and "reversed if not yet settled," but our transfers settle immediately and atomically (there is no pending state). We reinterpret cancellation as a **compensating reversal** allowed within 10 minutes: a guarded status flip (`UPDATE transfer SET status='CANCELLED' WHERE id=:id AND status='COMPLETED' AND created_at > NOW() - INTERVAL 10 MINUTE`) plus a reversal that moves the amount back from receiver to sender, both in one DB transaction. If the receiver's balance can no longer cover the reversal, the cancellation is rejected with `409` rather than driving the receiver negative.

## Considered Options

- **Pending → settled state machine** (cancel only while pending) — rejected. It matches the "not yet settled" wording literally but would violate the requirement that a transfer atomically deducts and credits on commit.
- **Allow the receiver to go negative on reversal** — rejected. Breaks the never-negative invariant.
- **Partial claw-back** — rejected as out of scope for this build.

## Consequences

- The guarded status flip makes double-cancellation naturally idempotent (0 rows affected ⇒ already cancelled / too old / missing).
- History stays append-only: cancellation records a linked reversal and flips status; nothing is deleted or rewritten.
