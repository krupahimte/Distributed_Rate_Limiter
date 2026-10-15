#!/usr/bin/env bash
# Smoke test for the docker compose stack: both instances behind nginx must share one limit.
# /api/search allows 5 requests per user, so a new user gets 5 x 200 (from both instances), then 429.
# Usage: scripts/smoke-test.sh            (stack must be running: docker compose up -d --wait)
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
user="smoke-$(date +%s)-$RANDOM"
declare -A instances=()

for i in 1 2 3 4 5; do
  body=$(curl -sf -H "X-User-Id: $user" "$BASE_URL/api/search?q=smoke") \
    || { echo "FAIL: request $i was not successful"; exit 1; }
  instance=$(sed -n 's/.*"instance":"\([^"]*\)".*/\1/p' <<<"$body")
  instances["$instance"]=1
  echo "request $i: 200 from $instance"
done

headers=$(curl -s -o /dev/null -D - -H "X-User-Id: $user" "$BASE_URL/api/search?q=smoke" | tr -d '\r')
status=$(head -n1 <<<"$headers" | awk '{print $2}')
echo "request 6: $status"

[ "$status" = "429" ] || { echo "FAIL: expected 429 on request 6, got $status"; exit 1; }
grep -qi '^Retry-After: [0-9]' <<<"$headers" || { echo "FAIL: 429 without Retry-After header"; exit 1; }
[ "${#instances[@]}" -ge 2 ] || { echo "FAIL: expected both instances to serve requests, got: ${!instances[*]}"; exit 1; }

echo "OK: limit of 5 enforced across instances: ${!instances[*]}"
