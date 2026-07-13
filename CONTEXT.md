# Balance Transfer

The domain of holding credit per user and moving it between users safely. This glossary fixes the vocabulary the code, tests, tickets, and API should use.

## Language

**User**:
A party that holds a balance and can send or receive transfers, identified by a unique userId.
_Avoid_: Customer, Account, Wallet holder

**Balance**:
The amount of credit a user currently holds. Never negative.
_Avoid_: Funds, Wallet, Amount (amount is per-transfer)

**Transfer**:
An atomic movement of a positive amount of credit from one user to another.
_Avoid_: Payment, Transaction, Trade

**Sender**:
The user a transfer moves credit from.
_Avoid_: Payer, From-user, Source

**Receiver**:
The user a transfer moves credit to.
_Avoid_: Payee, To-user, Destination, Recipient

**Reversal**:
A compensating transfer that undoes a prior transfer by moving the same amount back from receiver to sender.
_Avoid_: Refund, Rollback, Undo

**Cancellation**:
Reversing a recent transfer within the allowed window; produces a Reversal and marks the original transfer cancelled.
_Avoid_: Void, Delete, Revoke

**Settlement**:
The point a transfer becomes final. In this domain a transfer settles immediately on commit — there is no pending state.
_Avoid_: Clearing, Pending, In-flight

**Idempotency Key**:
A client-supplied identifier that guarantees a transfer request is applied at most once, even if retried.
_Avoid_: Request ID (in domain prose), Dedup token, Nonce

**Insufficient Funds**:
The condition where a user's balance is less than the amount a transfer or reversal requires.
_Avoid_: Overdraft, Negative balance, Shortfall

**Transfer History**:
The append-only record of every transfer where a given user is sender or receiver.
_Avoid_: Ledger (reserved for a future double-entry model), Statement, Activity log
