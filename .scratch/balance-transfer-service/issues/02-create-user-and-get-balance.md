# 02 — Create user & get balance

**What to build:** A client can create a user with an initial balance and read that user's current balance, end-to-end through the real database. This slice also stands up the shared error-response scaffold and the integration test harness that every later ticket reuses.

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] `POST /users` creates a user with a validated non-negative initial balance and returns `201`.
- [ ] Creating a user with an existing userId returns `409`.
- [ ] `GET /users/{userId}/balance` returns `200` with the balance; an unknown user returns `404`.
- [ ] Invalid input (missing fields, negative/zero-scale-violating balance) returns `400`.
- [ ] A `@RestControllerAdvice` maps domain errors to the consistent JSON body `{timestamp, status, error, message, path}`.
- [ ] Testcontainers (MySQL) integration harness is in place; endpoint tests cover the happy path and each error code.
