# Project: Balance Transfer Service (backend homework)

A RESTful balance-transfer service: atomic credit transfers between users.

Stack: Spring Boot 3, Java 21, Maven. MySQL (persistence), Redis (cache/lock), RocketMQ (async messaging).

## ⚑ Start here — resume in progress

**Read [`PROGRESS.md`](PROGRESS.md) first.** It is the handoff/status doc: what's done (all tickets 01–09), how to run, the architecture map, and the environment gotchas below. All tickets are merged to `main` (PR #1, merge commit `b986e50`); start new work on a fresh branch/PR off `main`.

Environment gotchas that will bite you if unread (details in PROGRESS.md):
- **Testcontainers doesn't work here** (Docker 29.x vs bundled docker-java → HTTP 400). Integration tests run against the **compose MySQL** — run `docker compose up -d` before `./mvnw verify`.
- **RocketMQ is off in tests** (`rocketmq.enabled=false`); the live smoke test is `@Disabled` (broker not host-reachable without `brokerIP1`).

Design canon to respect: `CONTEXT.md` (glossary), `docs/adr/*` (ADR-0001 DB-as-authority, ADR-0002 cancel-as-compensation), and `.scratch/balance-transfer-service/` (spec + `issues/NN-*.md` tickets). All nine tickets are implemented; the assignment is feature-complete.

## Layout
- `src/main/java/com/example/demo/` — `controller/` (REST), `service/` (business logic), `repository/` (data access), `model/` (entities/DTOs), `cache/` (Redis cache-aside), `event/` (RocketMQ DTOs, publisher, consumer handler), `config/` (beans)
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
- Domain vocabulary lives in `CONTEXT.md`. Use its terms, don't drift to synonyms.
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

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
