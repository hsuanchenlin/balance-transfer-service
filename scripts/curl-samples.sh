#!/usr/bin/env bash
#
# End-to-end walkthrough of the Balance Transfer Service API.
# Exercises all five endpoints plus the notable error cases (400 / 404 / 409).
#
# Prereqs: docker compose up -d  &&  ./mvnw spring-boot:run   (app on :8080)
# Usage:   ./scripts/curl-samples.sh
#
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
# Unique ids per run so re-running doesn't collide on the UNIQUE userId.
SUFFIX="$(date +%s)"
ALICE="alice_${SUFFIX}"
BOB="bob_${SUFFIX}"

# Pretty-print JSON if jq is present; otherwise pass through.
pp() { if command -v jq >/dev/null 2>&1; then jq .; else cat; fi; }

# call METHOD PATH [JSON_BODY] - prints the HTTP status line then the body.
call() {
  local method="$1" path="$2" body="${3:-}"
  echo "───────────────────────────────────────────────────────────"
  echo "► ${method} ${path}${body:+  ${body}}"
  if [[ -n "$body" ]]; then
    curl -sS -o /tmp/bt_body -w "  HTTP %{http_code}\n" \
         -X "$method" "${BASE}${path}" \
         -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS -o /tmp/bt_body -w "  HTTP %{http_code}\n" \
         -X "$method" "${BASE}${path}"
  fi
  [[ -s /tmp/bt_body ]] && pp < /tmp/bt_body
  echo
}

echo "== Balance Transfer Service - API walkthrough =="
echo "Base: ${BASE}   users: ${ALICE}, ${BOB}"
echo

echo "== 1) Create users (POST /users) =="
call POST /users "{\"userId\":\"${ALICE}\",\"initialBalance\":1000}"
call POST /users "{\"userId\":\"${BOB}\",\"initialBalance\":0}"

echo "== error: duplicate userId → 409 =="
call POST /users "{\"userId\":\"${ALICE}\",\"initialBalance\":50}"
echo "== error: negative initial balance → 400 =="
call POST /users "{\"userId\":\"neg_${SUFFIX}\",\"initialBalance\":-5}"

echo "== 2) Get balance (GET /users/{id}/balance) =="
call GET "/users/${ALICE}/balance"
echo "== error: unknown user → 404 =="
call GET "/users/ghost_${SUFFIX}/balance"

echo "== 3) Transfer (POST /transfers) =="
call POST /transfers "{\"fromUserId\":\"${ALICE}\",\"toUserId\":\"${BOB}\",\"amount\":150}"
call GET "/users/${ALICE}/balance"
call GET "/users/${BOB}/balance"

echo "== idempotency: same requestId twice applies once (both return the same transferId) =="
REQ="req-${SUFFIX}"
call POST /transfers "{\"fromUserId\":\"${ALICE}\",\"toUserId\":\"${BOB}\",\"amount\":25,\"requestId\":\"${REQ}\"}"
call POST /transfers "{\"fromUserId\":\"${ALICE}\",\"toUserId\":\"${BOB}\",\"amount\":25,\"requestId\":\"${REQ}\"}"
echo "== error: same requestId, different amount → 422 =="
call POST /transfers "{\"fromUserId\":\"${ALICE}\",\"toUserId\":\"${BOB}\",\"amount\":99,\"requestId\":\"${REQ}\"}"

echo "== error: insufficient funds → 409 =="
call POST /transfers "{\"fromUserId\":\"${BOB}\",\"toUserId\":\"${ALICE}\",\"amount\":999999}"
echo "== error: self-transfer → 400 =="
call POST /transfers "{\"fromUserId\":\"${ALICE}\",\"toUserId\":\"${ALICE}\",\"amount\":10}"
echo "== error: unknown user → 404 =="
call POST /transfers "{\"fromUserId\":\"${ALICE}\",\"toUserId\":\"ghost_${SUFFIX}\",\"amount\":10}"

echo "== 4) Transfer history (GET /transfers?userId=&page=&size=) =="
call GET "/transfers?userId=${ALICE}&page=0&size=10"
echo "== error: bad page size → 400 =="
call GET "/transfers?userId=${ALICE}&size=0"

echo "== 5) Cancel a transfer (POST /transfers/{id}/cancel) =="
# Make a fresh transfer we can cancel, capturing its id.
CREATE=$(curl -sS -X POST "${BASE}/transfers" -H 'Content-Type: application/json' \
              -d "{\"fromUserId\":\"${ALICE}\",\"toUserId\":\"${BOB}\",\"amount\":40}")
echo "  created: ${CREATE}"
if command -v jq >/dev/null 2>&1; then TID=$(echo "$CREATE" | jq -r .transferId); else
  TID=$(echo "$CREATE" | sed -E 's/.*"transferId":([0-9]+).*/\1/'); fi

call POST "/transfers/${TID}/cancel"        # → 200 CANCELLED, money returned
call POST "/transfers/${TID}/cancel"        # → 200 again (idempotent double-cancel)
call GET "/users/${ALICE}/balance"          # back to where it was before the 40
echo "== error: cancel unknown transfer → 404 =="
call POST "/transfers/999999999/cancel"

echo "== history now shows the CANCELLED original + the appended reversal =="
call GET "/transfers?userId=${ALICE}&page=0&size=20"

echo "Done."
