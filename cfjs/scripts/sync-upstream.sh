#!/usr/bin/env bash
# Copies the upstream spider loader files into the cfjs module unchanged, so they track FongMi/TV.
# Run from the repository root. Shims for App, UrlUtil and JsHost live only in cfjs and are not copied.
set -euo pipefail
src=app/src/main/java/com/fongmi/android/tv
dst=cfjs/app/src/main/java/com/fongmi/android/tv
files=(
  api/loader/JarLoader.java
  api/loader/SpiderLoader.java
  exception/SpiderLoadException.java
)
for f in "${files[@]}"; do
  mkdir -p "$(dirname "$dst/$f")"
  cp "$src/$f" "$dst/$f"
done
git rev-parse --short HEAD > cfjs/UPSTREAM_REVISION
echo "synced ${#files[@]} files from $(cat cfjs/UPSTREAM_REVISION)"
