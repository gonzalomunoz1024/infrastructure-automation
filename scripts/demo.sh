#!/usr/bin/env sh
# Runs the indexer in its demo profile next to the state service's demo: the state service on
# 18082 with its fake platform on 18090 (start it first from the console-state-service branch with
# its scripts/demo.sh), this service on 18085 following the state service's lists every two
# seconds, dev headers in place of the BFF's token, the in-memory engine unless OpenSearch is named.
#
#   scripts/demo.sh            start the indexer (foreground; Ctrl-C stops it)
#   scripts/demo.sh journey    with both running, search, page, suggest, count, and read the status
#
# Java 21 is all it needs. CONSOLE_SEARCH_ENGINE=opensearch CONSOLE_OPENSEARCH_URL=http://localhost:9200
# puts the index in OpenSearch (scripts/opensearch.sh runs one); CONSOLE_PORT moves the service off 18085;
# CONSOLE_STATE_URL points it at another state service.
set -eu

PORT="${CONSOLE_PORT:-18085}"
BASE="http://localhost:$PORT"
STATE="${CONSOLE_STATE_URL:-http://localhost:18082}"
ACCOUNT="123456789012"
cd "$(dirname "$0")/.."

json() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps(d, indent=2))' 2>/dev/null || cat; }
field() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }
encode() { python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }
as_dana() { curl -sS -H "X-Console-Account: $ACCOUNT" -H "X-Console-User: dana" -H "X-Console-Roles: operator" "$@"; }
as_bff() { curl -sS -H "X-Console-Service: console-bff" "$@"; }

if [ "${1:-}" = "journey" ]; then
  echo "== what the index holds of the account, and the newest event it applied"
  as_dana "$BASE/v1/index/status" | json
  echo
  echo "== the account's vms, by name, two per page"
  PAGE="$(as_dana "$BASE/v1/search?q=&type=vm&sort=name&limit=2")"
  echo "$PAGE" | json
  NEXT="$(echo "$PAGE" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("next") or "")')"
  if [ -n "$NEXT" ]; then
    echo
    echo "== the next page"
    as_dana "$BASE/v1/search?q=&type=vm&sort=name&limit=2&after=$NEXT" | json
  fi
  echo
  echo "== tag web-01 env=prod on the state service: the change reaches the index on the next poll"
  WEB="$(as_dana -G "$BASE/v1/search" --data-urlencode 'q=web-01' | field '["items"][0]["wfrn"]')"
  curl -sS -o /dev/null -w 'state service: %{http_code}\n' -H "X-Console-Account: $ACCOUNT" -H "X-Console-User: dana" \
    -H 'Content-Type: application/json' -d '{"set":{"env":"prod","team":"web"}}' "$STATE/v1/wfrn/$(encode "$WEB"):tag"
  sleep 3
  echo
  echo "== free text with a filter in the query: what is named like 'web' and tagged env=prod"
  as_dana -G "$BASE/v1/search" --data-urlencode 'q=web tag:env=prod' | json
  echo
  echo "== the type-ahead"
  as_dana "$BASE/v1/suggest?q=we" | json
  echo
  echo "== the list's counts: total, the state chips, drift"
  as_dana "$BASE/v1/counts?type=vm" | json
  echo
  echo "== another account's name answers 404, never 403"
  curl -sS -o /dev/null -w '%{http_code}\n' -H "X-Console-Account: $ACCOUNT" -H "X-Console-User: dana" "$BASE/v1/search?q=&accountId=210987654321"
  echo
  echo "== a rebuild from the state service, as the console's own services start it, and how it ended"
  RUN="$(as_bff -H 'Content-Type: application/json' -H "Idempotency-Key: demo-$(date +%s)" -d '{}' "$BASE/v1/index:rebuild")"
  echo "$RUN" | json
  RUN_ID="$(echo "$RUN" | field '["runId"]')"
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1
    DONE="$(as_bff "$BASE/v1/index/rebuilds/$RUN_ID")"
    [ "$(echo "$DONE" | field '["status"]')" = "running" ] || break
  done
  echo "$DONE" | json
  echo
  echo "== where the events come from"
  as_bff "$BASE/internal/sources" | json
  exit 0
fi

if ! curl -sS -o /dev/null "${CONSOLE_STATE_URL:-http://localhost:18082}/actuator/health" 2>/dev/null; then
  echo "the state service does not answer at ${CONSOLE_STATE_URL:-http://localhost:18082}: start its demo first (console-state-service branch, scripts/demo.sh)" >&2
fi
if [ ! -f build/libs/console-search-indexer.jar ]; then
  echo "building the jar first"
  ./gradlew bootJar -q
fi
echo "console-search-indexer on $BASE (engine: ${CONSOLE_SEARCH_ENGINE:-memory})"
echo "try: scripts/demo.sh journey"
exec java -jar build/libs/console-search-indexer.jar --spring.profiles.active=demo --server.port="$PORT"
