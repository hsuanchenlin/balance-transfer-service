# 01 — Setup & schema (prefactor)

**What to build:** The application boots and connects to MySQL, Redis, and RocketMQ from the provided `docker compose` stack, with the balance-transfer schema created and ready. This is the prefactor that makes every later slice easy.

**Blocked by:** None — can start immediately.

**Status:** done

- [x] `mysql-connector-j`, `spring-boot-starter-web`, and `spring-boot-starter-validation` added to the build.
- [x] `application.yaml` has working MySQL, Redis, and RocketMQ connection config (matching `docker-compose.yaml`: db `taskdb`, user `taskuser`).
- [x] `init.sql` creates the `account` table (userId PK, balance `DECIMAL(19,4)` non-negative, timestamps) and the `transfer` table (id PK, sender, receiver, amount `DECIMAL(19,4)`, status, `request_id` UNIQUE, reversal linkage, created_at) with indexes on sender, receiver, created_at.
- [x] `docker compose up -d` then app start succeeds; the existing `contextLoads` test passes against real infra.
