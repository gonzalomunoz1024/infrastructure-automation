#!/usr/bin/env sh
# Runs the cost service in its demo profile next to the state service's demo: the state service on
# 18082 with its fake platform on 18090 (start it first from the console-state-service branch with
# its scripts/demo.sh), this service on 18086 following the state service's lists every two
# seconds, prices from the bundled list prices, dev headers in place of the BFF's token. First
# sightings are spread over the last 90 days, so the Cost Explorer has a past.
#
#   scripts/demo.sh            start the service (foreground; Ctrl-C stops it)
#   scripts/demo.sh journey    with both running: cost by service and tag, one resource, a budget, a check, chargeback
#
# Java 21 is all it needs. CONSOLE_PORT moves the service off 18086; CONSOLE_STATE_URL points it at
# another state service.
set -eu

PORT="${CONSOLE_PORT:-18086}"
BASE="http://localhost:$PORT"
ACCOUNT="123456789012"
cd "$(dirname "$0")/.."

json() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps(d, indent=2))' 2>/dev/null || cat; }
encode() { python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }
as_dana() { curl -sS -H "X-Console-Account: $ACCOUNT" -H "X-Console-User: dana" -H "X-Console-Roles: account-admin" "$@"; }
as_bff() { curl -sS -H "X-Console-Service: console-bff" "$@"; }

if [ "${1:-}" = "journey" ]; then
  FROM="$(date -u -d '-30 days' +%Y-%m-%d 2>/dev/null || date -u -v-30d +%Y-%m-%d)"
  TO="$(date -u -d '+1 day' +%Y-%m-%d 2>/dev/null || date -u -v+1d +%Y-%m-%d)"
  MONTH="$(date -u +%Y-%m)"
  echo "== the last 30 days by service, with the forecast of this month"
  as_dana "$BASE/v1/cost?from=$FROM&to=$TO&groupBy=service" | json
  echo
  echo "== by month and the team tag"
  as_dana "$BASE/v1/cost?from=$FROM&to=$TO&granularity=month&groupBy=tag:team" | json
  echo
  echo "== what a 4 vCPU vm would cost"
  as_dana -H 'Content-Type: application/json' -d '{"type":"vm","attributes":{"cpu":4},"count":2}' "$BASE/v1/cost:estimate" | json
  echo
  echo "== a \$50 budget that blocks creates once over, alerting at 50% and 100%"
  as_dana -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-budget' \
    -d '{"name":"Account monthly","amount":50,"thresholds":[50,100],"blockCreates":true}' "$BASE/v1/budgets" | json
  echo
  echo "== may a new vm be created? (what the BFF asks before a preview)"
  as_bff -H 'Content-Type: application/json' -d "{\"accountId\":\"$ACCOUNT\",\"type\":\"vm\",\"monthly\":77}" "$BASE/v1/budgets:check" | json
  echo
  echo "== this month's chargeback by team (provisional until the month closes)"
  as_dana "$BASE/v1/chargeback?month=$MONTH&tag=team" | json
  echo
  echo "== the ledger's status"
  as_bff "$BASE/v1/ledger/status" | json
  exit 0
fi

./gradlew -q bootJar
exec java -jar build/libs/console-cost-service.jar --spring.profiles.active=demo --server.port="$PORT"
