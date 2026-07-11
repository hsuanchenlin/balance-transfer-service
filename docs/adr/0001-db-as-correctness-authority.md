# MySQL is the sole correctness authority for balances — no Redis distributed lock

The service may run as multiple app instances, which tempts an app-level Redis distributed lock around each transfer. We decided instead that MySQL/InnoDB is the single authority: the transfer is an atomic conditional update (`UPDATE account SET balance = balance - :amt WHERE user_id = :from AND balance >= :amt`) inside one DB transaction, with the two account updates applied in a deterministic order (sorted by userId) to avoid deadlocks. No version column, no `SELECT … FOR UPDATE`, and no Redis lock in the write path.

## Considered Options

- **Redis distributed lock (Redlock) around the transfer** — rejected. The database is already the shared synchronization point across every app instance, so the number of instances doesn't threaten the invariant. A Redis lock is *weaker* than the DB guarantee: a GC pause or clock skew can let the lock expire while a holder still believes it holds it, admitting a second holder. It adds a second failure domain for zero correctness gain.
- **Optimistic locking (version column + retry)** — rejected. A hot account thrashes on retries; more code for a worse worst case here.
- **Pessimistic `SELECT … FOR UPDATE`** — equivalent correctness, kept only as the alternative described in the README.

## Consequences

- The multi-instance hazard that *does* remain — duplicate/retried requests — is handled by an **idempotency key** (a UNIQUE constraint), not by a lock.
- Redis is confined to the read path (balance cache) and as the idempotency marker store. It is never consulted to decide whether a transfer may proceed.
