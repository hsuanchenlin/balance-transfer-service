# 08 — Cancel (compensating reversal)

**What to build:** A client can cancel a recent transfer within 10 minutes; the service posts a compensating reversal (moving the amount back from receiver to sender) and marks the original cancelled. Follows ADR-0002.

**Blocked by:** 03, 06

**Status:** ready-for-agent

- [ ] `POST /transfers/{transferId}/cancel` within 10 minutes reverses the transfer and returns `200`.
- [ ] The status flip is a guarded update — `... WHERE id=:id AND status='COMPLETED' AND created_at > NOW() - INTERVAL 10 MINUTE` — so a double-cancel is idempotent (0 rows ⇒ already cancelled / too old / missing). *(Guard encodes the ADR-0002 decision.)*
- [ ] The reversal is an atomic conditional update on the receiver; if the receiver can no longer cover it, the cancel is rejected with `409` (never drives the receiver negative).
- [ ] A cancel outside the 10-minute window returns `409`; an unknown transfer returns `404`.
- [ ] Cancellation records a linked reversal (append-only; nothing deleted) and emits a `TransferCancelled` event; cache is invalidated.
- [ ] Tests cover: happy reversal, too-late, receiver-can't-cover→409, and idempotent double-cancel.
