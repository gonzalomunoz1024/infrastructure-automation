#!/usr/bin/env bash
# Checks out every commit of services.lock as a detached worktree under .work/src/<image>, fetching
# a commit this clone lacks (CI's clone is shallow). A worktree already at its commit is kept.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=.work/src
mkdir -p "$SRC"
git worktree prune
while read -r image branch commit _; do
  case "$image" in '' | '#'*) continue ;; esac
  dir="$SRC/$image"
  if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" = "$commit" ]; then
    echo "$image: $branch @ ${commit:0:10} (already checked out)"
    continue
  fi
  git cat-file -e "$commit^{commit}" 2>/dev/null || git fetch --quiet --no-tags --depth 1 origin "$commit"
  if [ -e "$dir" ]; then git worktree remove --force "$dir" 2>/dev/null || rm -rf "$dir"; fi
  git worktree add --quiet --detach "$dir" "$commit"
  echo "$image: $branch @ ${commit:0:10}"
done < services.lock
