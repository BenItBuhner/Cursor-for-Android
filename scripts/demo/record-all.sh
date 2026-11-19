#!/usr/bin/env bash
# Runs every demo in this directory against the emulator that is up, one recording each, into $DEMO_OUT. A demo that
# fails is named and the rest still run: a recording of four features out of five is worth having.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
failed=()
for demo in "$here"/*.sh; do
  name="$(basename "$demo")"
  case "$name" in lib.sh | record-all.sh) continue ;; esac
  echo "::group::$name"
  if ! bash "$demo"; then
    failed+=("$name")
    adb shell pkill -INT screenrecord >/dev/null 2>&1 || true
  fi
  echo "::endgroup::"
done
if ((${#failed[@]})); then
  echo "::warning::demos that did not finish: ${failed[*]}"
  exit 1
fi
