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

The schema lives in [`init.sql`](init.sql) (tables `account`, `transfer`, `audit_log`, `outbox_event`). Docker mounts it and applies it **automatically on a fresh MySQL volume** - normally you do nothing.

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

Prefer Postman? The same walkthrough with per-request status/body assertions is in `scripts/balance-transfer.postman_collection.json` - import it into Postman and run the collection in order (the first request generates fresh user ids, so re-runs never collide), or run it headless:

```bash
npx newman run scripts/balance-transfer.postman_collection.json
```

Expected: 21 requests, 30 assertions, 0 failed.

## 5. Run the tests

```bash
./mvnw verify                 # unit (surefire *Test) + integration (failsafe *IT)
```

The integration tests are self-contained: `AbstractIntegrationTest` starts its own
Testcontainers MySQL (seeded with the repo-root `init.sql`) and Redis, so only a
Docker daemon is required - the compose stack does not need to be up.

Expected: **all tests pass, 1 skipped** (`RocketMqSmokeIT`, see below). `verify`
also enforces the JaCoCo coverage and SpotBugs quality gates (see the README's
"Test" section).

To also run the end-to-end RocketMQ smoke test (transfer → outbox relay → broker → consumer → `audit_log` row, ~30-60s; this one DOES need `docker compose up -d`):

```bash
ROCKETMQ_SMOKE=true ./mvnw -Dit.test=RocketMqSmokeIT verify
```

### Environment gotchas (read if a test fails)

1. **Docker Engine 29+ rejects Docker API handshakes below version 1.44** with HTTP 400, and the docker-java bundled with Testcontainers 1.21.x still defaults to 1.32. `AbstractIntegrationTest` pins the `api.version=1.44` system property in a static block before starting containers; if Testcontainers cannot connect on some other setup, check that pin first (it respects an external `-Dapi.version=...` override).
2. **RocketMQ is off during tests** (`rocketmq.enabled=false`) - the end-to-end `RocketMqSmokeIT` is opt-in (`ROCKETMQ_SMOKE=true`, command above) because first-run topic-route propagation makes it slow, not because it is broken: `broker.conf` sets `brokerIP1=127.0.0.1` so the compose broker is host-reachable, and `timerWheelEnable=false` so it boots fast under emulated Docker. If you edit `broker.conf`, recreate the container (`docker compose up -d --force-recreate rocketmq-broker`) - a plain restart keeps serving the pre-edit file because Docker for Mac binds single-file mounts by inode. The running app defaults RocketMQ on.

## 6. Shut down

```bash
docker compose down           # stop; add -v to also drop the data volume
```
