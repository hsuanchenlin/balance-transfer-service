# Project: Balance Transfer Service (backend homework)

A RESTful balance-transfer service: atomic credit transfers between users.

Stack: Spring Boot 3, Java 21, Maven. MySQL (persistence), Redis (cache/lock), RocketMQ (async messaging).

## ⚑ Start here — resume in progress

**Read [`PROGRESS.md`](PROGRESS.md) first.** It is the handoff/status doc: what's done (all tickets 01–09), how to run, the architecture map, and the environment gotchas below. All tickets are merged to `main` (PR #1, merge commit `b986e50`); start new work on a fresh branch/PR off `main`.

Environment gotchas that will bite you if unread (details in PROGRESS.md):
- **`./mvnw verify` is self-contained** - integration tests run on Testcontainers-managed MySQL/Redis (`AbstractIntegrationTest`); only a Docker daemon is needed, not the compose stack. Docker Engine 29+ rejects old Docker API handshakes, so the base class pins `api.version=1.44` for docker-java.
- **RocketMQ is off in tests** (`rocketmq.enabled=false`); the end-to-end smoke test is opt-in: `ROCKETMQ_SMOKE=true ./mvnw -Dit.test=RocketMqSmokeIT verify` (needs the compose stack up).

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
- `./mvnw verify` — full suite plus quality gates: JaCoCo >= 90% line coverage and SpotBugs High-priority findings both fail the build. CI (`.github/workflows/ci.yml`) runs the same on PRs and pushes to `main`.
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
