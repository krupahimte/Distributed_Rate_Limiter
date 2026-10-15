#!/usr/bin/env bash
# Smoke test for the playground frontend in the docker compose stack:
# the UI is served, and the API accepts and exposes headers to requests from the UI's origin.
# Usage: scripts/smoke-test-frontend.sh   (stack must be running: docker compose up -d --wait)
set -euo pipefail

UI_URL="${UI_URL:-http://localhost:3000}"
API_URL="${API_URL:-http://localhost:8080}"

fail() { echo "FAIL: $*"; exit 1; }

html=$(curl -sf "$UI_URL/") || fail "UI not reachable at $UI_URL"
grep -q '<title>Rate Limiter Playground</title>' <<<"$html" || fail "UI did not serve the playground page"
echo "UI page served"

script=$(sed -n 's/.*src="\(\/assets\/[^"]*\.js\)".*/\1/p' <<<"$html" | head -n1)
[ -n "$script" ] || fail "no JavaScript bundle referenced by the page"
curl -sf "$UI_URL$script" | grep -q "$API_URL" || fail "bundle $script does not point at $API_URL"
echo "bundle $script calls $API_URL"

# Preflight exactly as the browser sends it from the UI's origin
preflight=$(curl -s -o /dev/null -D - -X OPTIONS \
  -H "Origin: $UI_URL" \
  -H "Access-Control-Request-Method: GET" \
  -H "Access-Control-Request-Headers: x-user-id" \
  "$API_URL/api/playground/token-bucket" | tr -d '\r')
grep -qi "^Access-Control-Allow-Origin: $UI_URL$" <<<"$preflight" || fail "API does not allow origin $UI_URL"
echo "API allows origin $UI_URL"

response=$(curl -s -o /dev/null -D - -H "Origin: $UI_URL" -H "X-User-Id: smoke-ui-$RANDOM" \
  "$API_URL/api/playground/token-bucket" | tr -d '\r')
for header in X-RateLimit-Limit X-RateLimit-Remaining Retry-After; do
  grep -qi "^Access-Control-Expose-Headers:.*$header" <<<"$response" || fail "$header is not exposed to the browser"
done
echo "rate limit headers exposed to the browser"

echo "OK: playground frontend is served and can use the API"
