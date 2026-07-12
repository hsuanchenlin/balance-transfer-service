# 05 — Redis balance cache

**What to build:** Balance reads are served from Redis to shed load off the read-heavy path, and the cache is kept correct across transfers. Redis stays on the read path only — never consulted to decide whether a transfer may proceed (ADR-0001).

**Blocked by:** 03

**Status:** done

- [x] `GET /users/{userId}/balance` reads through Redis (cache-aside): hit serves from cache, miss loads from MySQL and populates it (`BalanceCache`, keys `balance:<userId>`, 5-min TTL).
- [x] A transfer invalidates the cached balance of both users — registered as an **afterCommit** eviction so a concurrent read can't repopulate it with uncommitted balances.
- [x] Test (real MySQL + Redis): cache hit still answers after the DB row is deleted behind the app's back (proves no DB query); a read after a transfer reflects the new balance (proves invalidation).
