# 03 — Transfer core + concurrency proof ⭐

**What to build:** A client can transfer a positive amount from one user to another, atomically, with the balance guaranteed never to go negative or be lost under concurrency. This is the heart of the service; its concurrency test is the submission's centerpiece.

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] `POST /transfers` debits the sender and credits the receiver in one DB transaction and returns `201` with the transfer id and status.
- [ ] The debit uses an atomic conditional update — `UPDATE account SET balance = balance - :amt WHERE user_id = :from AND balance >= :amt` (0 rows affected ⇒ insufficient funds ⇒ `409`). *(SQL inlined because it encodes the correctness decision from ADR-0001.)*
- [ ] The two account updates are applied in a deterministic order (sorted by userId) to prevent deadlocks.
- [ ] Unknown sender/receiver returns `404`; a self-transfer returns `400`; non-positive or over-scale amount returns `400`.
- [ ] A `COMPLETED` transfer row is appended (never mutated).
- [ ] **Concurrency test:** N threads issue concurrent transfers against shared accounts; asserts no lost updates, no negative balance, and conservation of total credit. Runs against real MySQL (Testcontainers).
