#!/usr/bin/env bash
# Starts the backend (demo profile, in-memory adapters) and the frontend dev server together.
#
#   scripts/dev.sh              backend on :8080, frontend on :3000
#   scripts/dev.sh --temporal   also runs the interpreter on a local Temporal dev server (:7233, UI :8233)
#   scripts/dev.sh --no-frontend
#
# Stops both when you press Ctrl-C.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPORAL=false
FRONTEND=true
for arg in "$@"; do
  case "$arg" in
    --temporal) TEMPORAL=true ;;
    --no-frontend) FRONTEND=false ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

command -v mvn >/dev/null || { echo "mvn not found (Java 21 + Maven 3.9 are needed)" >&2; exit 1; }
command -v npm >/dev/null || { echo "npm not found (Node 22 is needed)" >&2; exit 1; }

pids=()
cleanup() {
  trap - INT TERM EXIT
  for pid in "${pids[@]:-}"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup INT TERM EXIT

if $TEMPORAL; then
  if command -v temporal >/dev/null; then
    echo "▸ temporal dev server on :7233 (UI on :8233)"
    temporal server start-dev --headless=false --ui-port 8233 >"$ROOT/.temporal-dev.log" 2>&1 &
    pids+=($!)
  elif command -v docker >/dev/null; then
    echo "▸ temporal dev server through docker compose"
    (cd "$ROOT" && docker compose up -d temporal temporal-ui)
  else
    echo "--temporal needs the temporal CLI or docker" >&2; exit 1
  fi
  SPRING_PROFILES="demo,temporal"
  export PLATFORM_TEMPORAL_UI_URL="${PLATFORM_TEMPORAL_UI_URL:-http://localhost:8233}"   # each job's "Open in Temporal" link
else
  SPRING_PROFILES="demo"
fi

echo "▸ backend on :8080 (profiles: $SPRING_PROFILES)"
(cd "$ROOT/backend" && mvn -q spring-boot:run -Dspring-boot.run.profiles="$SPRING_PROFILES") &
pids+=($!)

if $FRONTEND; then
  if [ ! -d "$ROOT/node_modules" ]; then
    echo "▸ npm install"
    (cd "$ROOT" && npm install --no-audit --no-fund)
  fi
  echo "▸ frontend on :3000"
  (cd "$ROOT" && npm run dev) &
  pids+=($!)
fi

echo
echo "  app        http://localhost:3000"
echo "  api        http://localhost:8080/api/v1/catalog"
echo "  openapi    http://localhost:8080/openapi.json"
echo "  mcp        http://localhost:8080/api/v1/mcp"
$TEMPORAL && echo "  temporal   http://localhost:8233"
echo
wait
