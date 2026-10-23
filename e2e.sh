#!/usr/bin/env bash
# The customer console's full-system journey: checks out the commits of services.lock, builds
# their images, brings the stack up (docker-compose.yml) and runs the Playwright journey.
#
#   ./e2e.sh                 everything, then the stack comes down
#   ./e2e.sh --keep          leaves the stack up afterwards (run the journey again: cd journey && npx playwright test)
#   ./e2e.sh --no-build      uses the console-e2e/*:local images already built
#   ./e2e.sh -- <args>       passes the rest to playwright test
#
# The Playwright report, the JUnit results and the traces land in artifacts/; when anything fails,
# every container's log lands in artifacts/logs/ too.
set -euo pipefail
cd "$(dirname "$0")"
keep=0 build=1
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) keep=1 ;;
    --no-build) build=0 ;;
    --) shift; break ;;
    *) echo "unknown option $1 (--keep, --no-build, -- <playwright args>)" >&2; exit 2 ;;
  esac
  shift
done

rm -rf artifacts && mkdir -p artifacts

finish() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    echo "failed ($status): the containers' logs are in artifacts/logs/"
    mkdir -p artifacts/logs
    docker compose ps -a > artifacts/logs/ps.txt 2>&1 || true
    for s in $(docker compose ps -a --services 2>/dev/null); do
      docker compose logs --no-color --timestamps "$s" > "artifacts/logs/$s.log" 2>&1 || true
    done
  fi
  if [ "$keep" -eq 1 ]; then
    echo "the stack is still up (docker compose down -v to remove it)"
  else
    docker compose down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap finish EXIT

if [ "$build" -eq 1 ]; then
  scripts/checkout.sh
  scripts/build.sh
fi
docker compose down -v --remove-orphans >/dev/null 2>&1 || true
rm -f .work/journey/*.json
scripts/up.sh

cd journey
npm ci --no-audit --no-fund --loglevel=error
npx playwright install chromium >/dev/null
npx playwright test "$@"
