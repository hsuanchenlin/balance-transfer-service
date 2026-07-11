# Project: Balance Transfer Service (backend homework)

A RESTful balance-transfer service: atomic credit transfers between users.

Stack: Spring Boot 3, Java 21, Maven. MySQL (persistence), Redis (cache/lock), RocketMQ (async messaging).

## Layout
- `src/main/java/com/example/demo/` — `controller/` (REST), `service/` (business logic), `repository/` (data access), `model/` (entities/DTOs), `config/` (beans), `mq/` (RocketMQ producers/consumers)
- `src/main/resources/` — `application.*` config
- `src/test/java/com/example/demo/` — tests
- `docker-compose.yaml` — MySQL, Redis, RocketMQ (namesrv/broker/console)
- `docs/adr/` — architecture decision records (created lazily by domain-modeling)

## Commands
- `./mvnw spring-boot:run` — run locally
- `./mvnw test` — run tests
- `./mvnw -Dtest=<ClassName> test` — run a single test class
- `./mvnw package` — build the jar
- `docker compose up -d` — start MySQL/Redis/RocketMQ dependencies

## Conventions
- Domain vocabulary lives in `CONTEXT.md` (once created). Use its terms, don't drift to synonyms.
- Transfers must be atomic — no partial debit/credit.
- `userId` is unique.

## Never
- Commit secrets, `application-local` overrides, or the `.pem` file.
- Weaken transfer atomicity to make a test pass.
- Add dependencies without justification.

## Agent skills

### Issue tracker

Issues and specs live as local markdown under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
