# 02 — Create user & get balance

**What to build:** A client can create a user with an initial balance and read that user's current balance, end-to-end through the real database. This slice also stands up the shared error-response scaffold and the integration test harness that every later ticket reuses.

**Blocked by:** 01

**Status:** done

- [x] `POST /users` creates a user with a validated non-negative initial balance and returns `201`.
- [x] Creating a user with an existing userId returns `409`.
- [x] `GET /users/{userId}/balance` returns `200` with the balance; an unknown user returns `404`.
- [x] Invalid input (missing fields, negative/zero-scale-violating balance) returns `400`.
- [x] A `@RestControllerAdvice` maps domain errors to the consistent JSON body `{timestamp, status, error, message, path}`.
- [~] Integration harness in place (endpoint tests cover happy path + each error code) via `*IT` + maven-failsafe (`mvn verify`). **Deviation:** backed by the compose MySQL, not Testcontainers — this environment's Docker Engine 29.x is incompatible with the docker-java client bundled in the current Testcontainers (API handshake returns HTTP 400). Base class documents the one-line swap once the env is fixed.
