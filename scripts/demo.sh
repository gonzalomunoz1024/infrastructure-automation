#!/usr/bin/env sh
# Runs the state service in its demo profile: in-memory stores, the fake platform inside the same
# JVM on port 18090 seeded with the two console accounts, its feed followed over HTTP, and dev
# headers in place of the BFF's token. Then walks a VM through its life with curl.
#
#   scripts/demo.sh            start the service (foreground; Ctrl-C stops it)
#   scripts/demo.sh journey    with the service running, mint a VM, build it, tag it, delete it
#
# Java 21 is all it needs. Set CONSOLE_PORT to move the service off 18082.
set -eu

PORT="${CONSOLE_PORT:-18082}"
BASE="http://localhost:$PORT"
ACCOUNT="123456789012"
HDR_ACCOUNT="X-Console-Account: $ACCOUNT"
HDR_USER="X-Console-User: dana"
cd "$(dirname "$0")/.."

json() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps(d, indent=2))' 2>/dev/null || cat; }
field() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }
encode() { python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }

if [ "${1:-}" = "journey" ]; then
  echo "== the account's resources (the demo seed, imported at boot)"
  curl -sS -H "$HDR_ACCOUNT" -H "$HDR_USER" "$BASE/v1/resources?type=vm&limit=3" | json
  echo
  echo "== mint a VM (pending, version 1)"
  NAME="demo-$(date +%s)"
  IMAGE="$(curl -sS -H "$HDR_ACCOUNT" -H "$HDR_USER" "$BASE/v1/resources?type=image&limit=1" | field '["items"][0]["registryId"]')"
  MINTED="$(curl -sS -H "$HDR_ACCOUNT" -H "$HDR_USER" -H 'Content-Type: application/json' -H "Idempotency-Key: mint-$NAME" \
    -d "{\"type\":\"vm\",\"region\":\"global\",\"name\":\"$NAME\",\"workflow\":\"create-vm\",\"input\":{\"name\":\"$NAME\",\"image\":\"$IMAGE\",\"size\":\"small\",\"environment\":\"dev\"}}" \
    "$BASE/v1/resources:mint")"
  echo "$MINTED" | json
  WFRN="$(echo "$MINTED" | field '["wfrn"]')"
  ENC="$(encode "$WFRN")"
  echo
  echo "== the BFF would now start create-vm on the platform with X-Resource-Wfrn: $WFRN"
  echo "   (the demo's fake platform needs a signed on-behalf-of assertion, which the journey test"
  echo "    ResourceJourneyTest produces; here we watch the request and the trail instead)"
  echo
  echo "== the resource by wfrn (ETag is the version)"
  curl -sS -i -H "$HDR_ACCOUNT" -H "$HDR_USER" "$BASE/v1/wfrn/$ENC" | sed -n '1,12p'
  echo
  echo "== tag it"
  curl -sS -H "$HDR_ACCOUNT" -H "$HDR_USER" -H 'Content-Type: application/json' -d '{"set":{"env":"dev","owner-team":"web"}}' \
    "$BASE/v1/wfrn/$ENC:tag" | json
  echo
  echo "== its trail"
  curl -sS -H "$HDR_ACCOUNT" -H "$HDR_USER" "$BASE/v1/wfrn/$ENC/events" | json
  echo
  echo "== another account sees nothing (404, never 403)"
  curl -sS -o /dev/null -w '%{http_code}\n' -H 'X-Console-Account: 210987654321' -H 'X-Console-User: lee' "$BASE/v1/wfrn/$ENC"
  echo
  echo "== the feed this service follows, as another service sees it"
  curl -sS -H 'X-Console-Service: console-bff' "$BASE/internal/feed" | json
  exit 0
fi

if [ ! -f build/libs/console-state-service.jar ]; then
  echo "building the jar first"
  ./gradlew bootJar -q
fi
echo "console-state-service on $BASE, the fake platform on http://localhost:18090"
echo "try: scripts/demo.sh journey"
exec java -jar build/libs/console-state-service.jar --spring.profiles.active=demo --server.port="$PORT"
