# 07 — Transfer history

**What to build:** A client can list the transfers a user was involved in — as sender or receiver — paginated and most-recent-first.

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] `GET /transfers?userId=&page=&size=` returns transfers where the user is sender **or** receiver.
- [ ] Results are ordered most-recent-first (`created_at DESC, id DESC`) and paginated by `page`/`size` with sane defaults and bounds.
- [ ] Both `COMPLETED` and `CANCELLED` transfers appear, with their status.
- [ ] Test: a user with mixed sent/received transfers gets a correctly ordered, correctly paged result.
