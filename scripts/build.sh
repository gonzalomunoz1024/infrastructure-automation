#!/usr/bin/env bash
# Builds the console-e2e/<image>:local images from the worktrees scripts/checkout.sh made:
#
#   1. console-libs into a private Maven repository (.work/m2), and platform-fake-app's boot jar
#   2. each Java service's boot jar against it
#   3. each console-web commit's production build (live mode), and the feed bridge's dependencies
#   4. the images: the service's own Dockerfile, else images/<image>/Dockerfile
#
#   scripts/build.sh [image...]    only these images (after console-libs, which is always built)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
SRC=.work/src
M2="$ROOT/.work/m2"
mkdir -p "$M2"
only=" $* "

gradle() { (cd "$1" && shift && ./gradlew --no-daemon --quiet --console=plain -Dmaven.repo.local="$M2" "$@"); }
image() { # <image> <context>
  local df="$2/Dockerfile"
  [ -f "$df" ] || df="images/$1/Dockerfile"
  [ "$1" = console-web-cost ] && df=images/console-web/Dockerfile
  docker build --quiet -t "console-e2e/$1:local" -f "$df" "$2" >/dev/null
  echo "built console-e2e/$1:local"
}

echo "console-libs: publishing to $M2, platform-fake-app"
gradle "$SRC/platform-fake-app" :build-logic:publishToMavenLocal publishToMavenLocal :platform-fake-app:bootJar -x test
[ "$only" = "  " ] || [[ "$only" == *" platform-fake-app "* ]] && image platform-fake-app "$SRC/platform-fake-app"

while read -r name _ _ _; do
  case "$name" in '' | '#'* | platform-fake-app) continue ;; esac
  [ "$only" = "  " ] || [[ "$only" == *" $name "* ]] || continue
  dir="$SRC/$name"
  if [ -x "$dir/gradlew" ]; then
    echo "$name: boot jar"
    rm -rf "$dir/build/libs"
    gradle "$dir" bootJar -x test
  else
    echo "$name: production build of the console"
    (cd "$dir" && npm ci --no-audit --no-fund --loglevel=error && npm run build --workspace console --silent)
  fi
  image "$name" "$dir"
done < services.lock

if [ "$only" = "  " ] || [[ "$only" == *" platform-feed-bridge "* ]]; then
  (cd images/platform-feed-bridge && npm ci --omit=dev --no-audit --no-fund --loglevel=error)
  image platform-feed-bridge images/platform-feed-bridge
fi
