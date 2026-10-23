#!/usr/bin/env sh
# Runs the drift service in its demo profile: in-memory stores, the fake platform inside the same
# JVM on port 18090 seeded with the two console accounts, a stand-in of the state service fed from
# the fake's registry, fast loops, and dev headers in place of the BFF's token. The journey then
# changes VMs behind the console's back and shows drift being found, reverted and accepted.
#
#   scripts/demo.sh            start the service (foreground; Ctrl-C stops it)
#   scripts/demo.sh journey    with the service running: drift two VMs, revert one, accept the other
#
# Java 21 and python3 (to read JSON) are all it needs. Set CONSOLE_PORT to move the service off
# 18084. To run against a real state service instead of the stand-in:
#   CONSOLE_STATE_URL=http://localhost:18082 scripts/demo.sh
# (that state service must read the same fake platform, http://localhost:18090).
set -eu

PORT="${CONSOLE_PORT:-18084}"
BASE="http://localhost:$PORT"
PLATFORM="http://localhost:18090"
ACCOUNT="123456789012"
# dana, a developer of the compute account; the groups are what the platform's delegation checks on a revert
HDR_ACCOUNT="X-Console-Account: $ACCOUNT"
HDR_USER="X-Console-User: dana"
HDR_GROUPS="X-Console-Groups: team-compute,developer"
HDR_ROLES="X-Console-Roles: operator"
HDR_SERVICE="X-Console-Service: console-bff"
cd "$(dirname "$0")/.."

json() { python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin), indent=2))' 2>/dev/null || cat; }
field() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }
urlencode() { python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }
as_dana() { curl -sS -H "$HDR_ACCOUNT" -H "$HDR_USER" -H "$HDR_GROUPS" -H "$HDR_ROLES" "$@"; }

# the watched dev VMs of the account, one per line: wfrn, vSphere id, name
dev_vms() {
  curl -sS -H "$HDR_SERVICE" "$BASE/internal/watches?accountId=$ACCOUNT&type=vm&limit=200" | python3 -c '
import json, sys
for w in json.load(sys.stdin)["items"]:
    if w.get("environment") == "dev" and w.get("providerId") and w.get("source") != "discover":
        print(w["wfrn"], w["providerId"], w.get("name") or "")'
}

# changes one field of a VM in the fake's recorded world, as someone working in vSphere directly would
change_at_provider() {
  curl -sS -H 'Content-Type: application/json' \
    -d "{\"provider\":\"vsphere\",\"match\":{\"id\":\"$1\"},\"set\":{\"$2\":$3}}" "$PLATFORM/api/v1/demo/world:mutate" > /dev/null
}

# waits for the resource's open finding and prints its id
await_finding() {
  for _ in $(seq 1 40); do
    id="$(as_dana "$BASE/v1/findings?wfrn=$(urlencode "$1")" | python3 -c '
import json, sys
items = json.load(sys.stdin)["items"]
print(items[0]["findingId"] if items else "")')"
    if [ -n "$id" ]; then echo "$id"; return 0; fi
    sleep 1
  done
  echo "no finding opened for $1" >&2
  return 1
}

# waits for the finding to close and prints it
await_closed() {
  for _ in $(seq 1 60); do
    f="$(as_dana "$BASE/v1/findings/$1")"
    if echo "$f" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin)["status"]=="closed" else 1)'; then echo "$f" | json; return 0; fi
    sleep 1
  done
  echo "the finding $1 did not close" >&2
  return 1
}

if [ "${1:-}" = "journey" ]; then
  echo "== the account's open findings"
  as_dana "$BASE/v1/findings/summary" | json
  VMS="$(dev_vms)"
  if [ "$(echo "$VMS" | grep -c .)" -lt 2 ]; then
    echo "the demo needs two watched dev VMs; is the service running (scripts/demo.sh)?" >&2
    exit 1
  fi
  set -- $(echo "$VMS" | sed -n 1p)
  WFRN1="$1" VM1="$2" NAME1="${3:-$2}"
  set -- $(echo "$VMS" | sed -n 2p)
  WFRN2="$1" VM2="$2" NAME2="${3:-$2}"

  echo
  echo "== someone gives $NAME1 64 GB of memory in vSphere, behind the console's back"
  change_at_provider "$VM1" memoryGb 64
  echo "   the hot tier reads it again within 10 s in the demo"
  FINDING1="$(await_finding "$WFRN1")"
  as_dana "$BASE/v1/findings/$FINDING1" | json

  echo
  echo "== one click: revert (the type's update workflow runs with the desired values, on behalf of dana; never on its own)"
  as_dana -H 'Content-Type: application/json' -H "Idempotency-Key: revert-$FINDING1" \
    -d '{"reason":"put it back"}' "$BASE/v1/findings/$FINDING1:revert" | json
  echo
  echo "== the finding closes as reverted once the job has run and the next read agrees"
  await_closed "$FINDING1"

  echo
  echo "== someone gives $NAME2 4 more vCPUs; this time the change was meant"
  change_at_provider "$VM2" cpu 8
  FINDING2="$(await_finding "$WFRN2")"
  echo "== accept: the observed values become the desired ones on the state service"
  as_dana -H 'Content-Type: application/json' -H "Idempotency-Key: accept-$FINDING2" \
    -d '{"reason":"sized up for the load test"}' "$BASE/v1/findings/$FINDING2:accept" | json

  echo
  echo "== another account sees nothing of it (404, never 403)"
  curl -sS -o /dev/null -w '%{http_code}\n' -H 'X-Console-Account: 210987654321' -H 'X-Console-User: ana' "$BASE/v1/findings/$FINDING2"
  echo
  echo "== ignoring works the same way: POST /v1/findings/{id}:ignore with an optional \"until\""
  exit 0
fi

if [ ! -f build/libs/console-drift-service.jar ]; then
  echo "building the jar first"
  ./gradlew bootJar -q
fi
echo "console-drift-service on $BASE, the fake platform on $PLATFORM"
echo "in another terminal: scripts/demo.sh journey"
if [ -n "${CONSOLE_STATE_URL:-}" ]; then
  exec java -jar build/libs/console-drift-service.jar --spring.profiles.active=demo --server.port="$PORT" \
    --console.state.mode=http --console.state.base-url="$CONSOLE_STATE_URL"
fi
exec java -jar build/libs/console-drift-service.jar --spring.profiles.active=demo --server.port="$PORT"
