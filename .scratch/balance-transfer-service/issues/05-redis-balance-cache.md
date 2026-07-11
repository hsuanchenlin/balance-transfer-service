# 05 — Redis balance cache

**What to build:** Balance reads are served from Redis to shed load off the read-heavy path, and the cache is kept correct across transfers. Redis stays on the read path only — never consulted to decide whether a transfer may proceed (ADR-0001).

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] `GET /users/{userId}/balance` reads through Redis (cache-aside): hit serves from cache, miss loads from MySQL and populates it.
- [ ] A transfer invalidates (or updates) the cached balance of both users synchronously on the authoritative write, so a subsequent read never returns a stale balance.
- [ ] Test (Testcontainers MySQL + Redis): a read after a transfer reflects the new balance; a cache hit does not query the DB.
