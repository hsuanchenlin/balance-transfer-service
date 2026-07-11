# 09 — Submission docs

**What to build:** The submission is reviewer-ready: a README that narrates the design rationale (not just how to run it), setup/data scripts, and sample requests. This is where the senior-signal reasoning is made visible.

**Blocked by:** 02, 03, 04, 05, 06, 07, 08

**Status:** ready-for-agent

- [ ] `README` documents: how to run, the API contract, and — crucially — the load-bearing design rationale: DB-as-authority vs Redis lock (with the Redlock failure mode), idempotency-not-lock for the multi-instance case, Redis on the read path only, MQ for async side-effects, and cancel-as-compensation with the `409` edge. Reference ADR-0001 and ADR-0002.
- [ ] `HELP.md` holds any setup or data scripts (per the assignment's instruction).
- [ ] curl samples (and/or a Postman collection) exercise all five endpoints including the error cases.
- [ ] A reviewer can clone, `docker compose up -d`, run, and reproduce a transfer + cancel from the docs alone.
