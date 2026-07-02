#!/usr/bin/env bash
# The 60-second reviewer path per DESIGN.md §1/§18: after `docker compose up`, this fires three
# real payments against the running stack and prints what happened - a JPY happy path, a
# duplicate-Idempotency-Key replay, and an insufficient-funds failure - then checks
# reconciliation. Every number below is read back from the actual services, not asserted blind.
set -euo pipefail
cd "$(dirname "$0")"

GATEWAY_URL="http://localhost:8080"
PROCESSOR_URL="http://localhost:8081"

json_field() {
  # $1 = JSON text, $2 = field name (string value only)
  echo "$1" | sed -E "s/.*\"$2\"\s*:\s*\"([^\"]*)\".*/\1/"
}

echo "== Waiting for api-gateway to be healthy =="
for i in $(seq 1 60); do
  if curl -sf "$GATEWAY_URL/actuator/health" | grep -q '"status":"UP"'; then
    break
  fi
  sleep 2
done
if ! curl -sf "$GATEWAY_URL/actuator/health" | grep -q '"status":"UP"'; then
  echo "api-gateway never became healthy - is 'docker compose up' running?" >&2
  exit 1
fi
echo "api-gateway is healthy."
echo

echo "== Minting a demo JWT (POST /auth/token) =="
TOKEN_RESPONSE=$(curl -sf -X POST "$GATEWAY_URL/auth/token")
TOKEN=$(json_field "$TOKEN_RESPONSE" token)
echo "token: ${TOKEN:0:24}..."
echo

post_payment() {
  curl -s -w '\n%{http_code}' -X POST "$GATEWAY_URL/payments" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Idempotency-Key: $1" \
    -H "Content-Type: application/json" \
    -d "$2"
}

wait_for_payment_status() {
  # $1 = paymentId, prints the final status once it leaves PENDING (or times out)
  for i in $(seq 1 15); do
    STATUS=$(docker compose exec -T postgres psql -U postgres -d processor -tA \
      -c "SELECT status FROM payments WHERE id = '$1';" 2>/dev/null | tr -d '[:space:]')
    if [ -n "$STATUS" ] && [ "$STATUS" != "PENDING" ]; then
      echo "$STATUS"
      return
    fi
    sleep 1
  done
  echo "STILL_PENDING_AFTER_15s"
}

echo "=================================================================="
echo "Scenario 1: JPY happy path (jpy-funded -> jpy-low, 1000 JPY)"
echo "=================================================================="
KEY1="demo-happy-$(date +%s)"
RESP=$(post_payment "$KEY1" '{"fromAccount":"jpy-funded","toAccount":"jpy-low","amount":"1000","currency":"JPY"}')
HTTP_CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
echo "HTTP $HTTP_CODE: $BODY"
PAYMENT_ID_1=$(json_field "$BODY" paymentId)
FINAL_STATUS_1=$(wait_for_payment_status "$PAYMENT_ID_1")
echo "final status (processed async via Kafka + the ledger transaction, §10): $FINAL_STATUS_1"
echo

echo "=================================================================="
echo "Scenario 2: duplicate Idempotency-Key replay (same key as Scenario 1)"
echo "=================================================================="
RESP2=$(post_payment "$KEY1" '{"fromAccount":"jpy-funded","toAccount":"jpy-low","amount":"1000","currency":"JPY"}')
HTTP_CODE_2=$(echo "$RESP2" | tail -1)
BODY_2=$(echo "$RESP2" | sed '$d')
echo "HTTP $HTTP_CODE_2: $BODY_2"
if [ "$HTTP_CODE_2" = "200" ] && [ "$(json_field "$BODY_2" paymentId)" = "$PAYMENT_ID_1" ]; then
  echo "idempotency held: same key returned the original response (200, same paymentId), no reprocessing."
else
  echo "UNEXPECTED: expected 200 with paymentId=$PAYMENT_ID_1" >&2
fi
echo

echo "=================================================================="
echo "Scenario 3: insufficient funds (jpy-low -> jpy-funded, 1,000,000 JPY)"
echo "=================================================================="
KEY3="demo-insufficient-$(date +%s)"
RESP3=$(post_payment "$KEY3" '{"fromAccount":"jpy-low","toAccount":"jpy-funded","amount":"1000000","currency":"JPY"}')
HTTP_CODE_3=$(echo "$RESP3" | tail -1)
BODY_3=$(echo "$RESP3" | sed '$d')
echo "HTTP $HTTP_CODE_3: $BODY_3"
PAYMENT_ID_3=$(json_field "$BODY_3" paymentId)
FINAL_STATUS_3=$(wait_for_payment_status "$PAYMENT_ID_3")
FAILURE_REASON=$(docker compose exec -T postgres psql -U postgres -d processor -tA \
  -c "SELECT failure_reason FROM payments WHERE id = '$PAYMENT_ID_3';" 2>/dev/null | tr -d '[:space:]')
echo "final status: $FINAL_STATUS_3 (failure_reason: $FAILURE_REASON)"
echo "this is a normal, committed business outcome (§10) - no retry, no DLQ, no error logged."
echo

echo "=================================================================="
echo "Reconciliation check (GET :8081/reconciliation)"
echo "=================================================================="
RECONCILIATION=$(curl -sf "$PROCESSOR_URL/reconciliation")
echo "$RECONCILIATION"
if [ "$RECONCILIATION" = "[]" ]; then
  echo "reconciliation clean: no mismatches detected."
else
  echo "reconciliation reported issues - see above." >&2
fi
echo

echo "== Done. Explore further at $GATEWAY_URL/swagger-ui, or Grafana at http://localhost:3000 =="
