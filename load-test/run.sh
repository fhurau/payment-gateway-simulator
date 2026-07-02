#!/usr/bin/env bash
# Load-test artifact per DESIGN.md §15/§18. Brings up the stack (with the Phase 7 rate limiter
# raised - this measures ledger/idempotency throughput, not the rate limiter, already verified in
# Phase 7), runs k6 against it, then proves idempotency held under real concurrent load by
# checking Postgres directly - a k6 HTTP check alone can't see the DB-level guarantee.
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1

echo "==> Starting stack (loadtest profile: rate limit raised)"
docker compose -f docker-compose.yml -f load-test/docker-compose.override.yml up -d --build

echo "==> Waiting for api-gateway to be healthy"
until [ "$(docker inspect -f '{{.State.Health.Status}}' payment-gateway-simulator-api-gateway-1 2>/dev/null)" = "healthy" ]; do
  sleep 2
done

NETWORK="payment-gateway-simulator_default"
SHARED_IDEMPOTENCY_KEY="loadtest-idempotency-burst-$(date +%s)-$$"

echo "==> Running k6 load test"
HOST_PWD="$(pwd -W 2>/dev/null || pwd)"
docker run --rm --network "$NETWORK" \
  -e BASE_URL=http://api-gateway:8080 \
  -e SHARED_IDEMPOTENCY_KEY="$SHARED_IDEMPOTENCY_KEY" \
  -v "$HOST_PWD/load-test:/scripts" \
  grafana/k6 run /scripts/k6-script.js

echo "==> Verifying idempotency held under the concurrent burst (Postgres is the source of truth)"
# Exact match, not a LIKE wildcard: the pgdata volume persists across runs (docker compose down
# without -v), so a prefix pattern would also match earlier runs' rows and produce a false
# "violation" that has nothing to do with this run's concurrency.
COUNT=$(docker compose exec -T postgres psql -U postgres -d gateway -tA \
  -c "SELECT count(*) FROM idempotency_keys WHERE key = '$SHARED_IDEMPOTENCY_KEY';")
echo "idempotency_keys rows matching this run's burst key: $COUNT (expected: 1)"
if [ "$COUNT" != "1" ]; then
  echo "IDEMPOTENCY VIOLATION: expected exactly 1 row, got $COUNT" >&2
  exit 1
fi
echo "idempotency held: 20 concurrent requests with the same Idempotency-Key produced exactly 1 record"

echo "==> Tearing down loadtest stack"
docker compose -f docker-compose.yml -f load-test/docker-compose.override.yml down
