# HELP - setup, data scripts, and running

Everything a reviewer needs to clone, boot the stack, and reproduce a transfer + cancel.

## Prerequisites

- JDK 21 (`java -version` → 21)
- Docker + Docker Compose
- No local Maven needed - use the bundled wrapper (`./mvnw`)

## 1. Start the infrastructure

```bash
docker compose up -d
```

| Service | Port | Notes |
|---------|------|-------|
| MySQL | 3306 | db `taskdb`, user `taskuser` / `taskpass` (root/`root`) |
| Redis | 6379 | balance cache + idempotency markers |
| RocketMQ nameserver | 9876 | |
| RocketMQ broker | 10911 / 10909 | |
| RocketMQ console | 8088 | http://localhost:8088 |

Wait until MySQL is healthy:

```bash
docker compose ps            # STATUS should read "healthy" for mysql
```

## 2. Schema / data script

The schema lives in [`init.sql`](init.sql) (tables `account`, `transfer`, `audit_log`). Docker mounts it and applies it **automatically on a fresh MySQL volume** - normally you do nothing.

If the MySQL volume already exists (schema changed but volume is old), apply it by hand:

```bash
docker exec -i mysql mysql -uroot -proot taskdb < init.sql
```

`init.sql` is idempotent (`CREATE TABLE IF NOT EXISTS`), so re-running it is safe.

To wipe all data and start clean:

```bash
docker compose down -v && docker compose up -d     # -v drops the volume; init.sql re-applies
```

To seed a couple of users quickly:

```bash
docker exec -i mysql mysql -uroot -proot taskdb <<'SQL'
INSERT INTO account (user_id, balance) VALUES ('user_001', 1000), ('user_002', 0)
  ON DUPLICATE KEY UPDATE balance = VALUES(balance);
SQL
```

## 3. Run the app

```bash
./mvnw spring-boot:run        # starts on http://localhost:8080
```

Connection settings are in `src/main/resources/application.yaml` and already point at the compose stack (`localhost:3306` / `localhost:6379` / `localhost:9876`). RocketMQ defaults to `enabled: true`.

## 4. Exercise the API

```bash
./scripts/curl-samples.sh     # end-to-end walkthrough of all 5 endpoints + error cases
```

The script creates users, transfers, reads balances and history, cancels a transfer, and shows the `404`/`409`/`400` error responses. It needs the app running on `:8080`.

## 5. Run the tests

```bash
docker compose up -d          # the integration tests use the real compose MySQL + Redis
./mvnw verify                 # unit (surefire *Test) + integration (failsafe *IT)
```

Expected: **all tests pass, 1 skipped** (`RocketMqSmokeIT`, see below).

### Environment gotchas (read if a test fails)

1. **Integration tests require the compose stack running.** They boot the full app against `localhost:3306` / `6379` and clean the tables + `balance:*` keys before each test. (Testcontainers was the intended provider but this box's Docker Engine 29.x is incompatible with the bundled docker-java client; `AbstractIntegrationTest` documents the one-line swap back once that's resolved.)
2. **RocketMQ is off during tests** (`rocketmq.enabled=false`) - the compose broker advertises a container-internal address not reachable from host test clients. The end-to-end `RocketMqSmokeIT` is therefore `@Disabled` with manual-run instructions in its Javadoc; `broker.conf` sets `brokerIP1=127.0.0.1` so a **restarted** stack is host-reachable for that manual run. The running app defaults RocketMQ on.

## 6. Shut down

```bash
docker compose down           # stop; add -v to also drop the data volume
```
