# 03 — Transfer core + concurrency proof ⭐

**What to build:** A client can transfer a positive amount from one user to another, atomically, with the balance guaranteed never to go negative or be lost under concurrency. This is the heart of the service; its concurrency test is the submission's centerpiece.

**Blocked by:** 02

**Status:** done

- [x] `POST /transfers` debits the sender and credits the receiver in one DB transaction and returns `201` with the transfer id and status.
- [x] The debit uses an atomic conditional update — `UPDATE account SET balance = balance - :amt WHERE user_id = :from AND balance >= :amt` (0 rows affected ⇒ insufficient funds ⇒ `409`). *(SQL inlined because it encodes the correctness decision from ADR-0001.)*
- [x] The two account updates are applied in a deterministic order (sorted by userId) to prevent deadlocks.
- [x] Unknown sender/receiver returns `404`; a self-transfer returns `400`; non-positive or over-scale amount returns `400`.
- [x] A `COMPLETED` transfer row is appended (never mutated).
- [x] **Concurrency test:** two suites — an over-spend test (200 concurrent transfers vs 100 funds ⇒ exactly 100 succeed, sender→0, receiver→100) and a bidirectional test (600 opposing transfers ⇒ total conserved, never negative). Runs against real MySQL (compose; see ticket 02 note on the Testcontainers/Docker-29 deviation).
