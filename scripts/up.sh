#!/usr/bin/env bash
# Brings the e2e stack up from images already built (scripts/build.sh), in three stages:
#
#   1. fresh dev keys and env files (scripts/keys.mjs, scripts/env.mjs)
#   2. the infrastructure, the dev SSO and platform-fake, then the console-internal service
#      account's token, which platform-fake makes from its seed and logs at boot
#   3. everything else, waiting until every health check passes
#
#   scripts/up.sh            uses .work/ under the repository
set -euo pipefail
cd "$(dirname "$0")/.."
WORK=.work
compose() { docker compose "$@"; }

node scripts/keys.mjs "$WORK"
node scripts/env.mjs "$WORK"

echo "starting the infrastructure, the dev SSO and platform-fake"
compose up -d --wait --wait-timeout 300 mongo kafka kafka-init opensearch temporal-db temporal dex platform-fake

# platform-fake logs one line per demo service account: "<id> (<name>): bearer iaf_..."
token=""
for _ in $(seq 1 60); do
  token=$(compose logs --no-log-prefix platform-fake 2>/dev/null | sed -n 's/.*sa-console-internal (console-internal): bearer \(iaf_[A-Za-z0-9]*\).*/\1/p' | tail -1)
  [ -n "$token" ] && break
  sleep 1
done
if [ -z "$token" ]; then
  echo "platform-fake never logged the console-internal service account's token" >&2
  exit 1
fi
node scripts/env.mjs "$WORK" "$token"
# the journey reads it too: platform-fake's demo route (step 6) and its public images
printf '%s\n' "$token" > "$WORK/platform-internal-token"

echo "starting the console"
compose up -d --wait --wait-timeout 420
compose ps --format 'table {{.Service}}\t{{.Status}}'
