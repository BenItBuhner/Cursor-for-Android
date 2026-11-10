#!/usr/bin/env bash
# What the demo recordings are driven with: a running emulator, adb, and the app's own labels. Every demo runs
# against the app's built-in demo account, so nothing here needs a Cursor sign-in or reaches cursor.com.
#
# A step names what a person would press ("Try the demo"), not where it is on screen: the label is looked up in the
# accessibility tree (uiautomator), by its text or its content description, and its centre is tapped.
set -euo pipefail

APP="${DEMO_APP:-com.cursorforandroid.debug}"
ACTIVITY="${DEMO_ACTIVITY:-com.cursorforandroid.MainActivity}"
OUT="${DEMO_OUT:-demo-out}"
mkdir -p "$OUT"

# To stderr: a step's stdout is the coordinates it found, read by its caller.
log() { printf '[demo] %s\n' "$*" >&2; }

# Prints "x y", the centre of the first node whose text or content description is (or, with a third argument of
# "contains", contains) the label; fails when there is none.
_centre_of() {
  python3 - "$1" "$2" "${3:-exact}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, label, mode = sys.argv[1:4]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(1)
for node in root.iter("node"):
    for key in ("text", "content-desc"):
        value = node.get(key) or ""
        if value == label or (mode == "contains" and label in value):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                if x2 > x1 and y2 > y1:
                    print((x1 + x2) // 2, (y1 + y2) // 2)
                    sys.exit(0)
sys.exit(1)
PY
}

# Prints every label on screen, one per line: what a failed step could have pressed instead.
_labels() {
  python3 - "$1" <<'PY'
import sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(1)
seen = []
for node in root.iter("node"):
    for key in ("text", "content-desc"):
        value = (node.get(key) or "").strip()
        if value and value not in seen:
            seen.append(value)
for value in seen:
    print("  | " + value[:120])
PY
}

_dump() {
  adb shell uiautomator dump /sdcard/demo-ui.xml >/dev/null 2>&1 || return 1
  adb pull /sdcard/demo-ui.xml "$OUT/.ui.xml" >/dev/null 2>&1
}

# wait_for <label> [seconds] [exact|contains]: until the label is on screen. Prints its centre.
wait_for() {
  local label="$1" seconds="${2:-30}" mode="${3:-exact}" deadline centre
  deadline=$((SECONDS + seconds))
  while ((SECONDS < deadline)); do
    if _dump && centre="$(_centre_of "$OUT/.ui.xml" "$label" "$mode")"; then
      echo "$centre"
      return 0
    fi
    sleep 0.5
  done
  log "never saw \"$label\"; what was on screen is in $OUT/failed-ui.xml and failed-screen.png, and its labels were:"
  _labels "$OUT/.ui.xml" >&2 || true
  cp "$OUT/.ui.xml" "$OUT/failed-ui.xml" 2>/dev/null || true
  adb exec-out screencap -p >"$OUT/failed-screen.png" 2>/dev/null || true
  return 1
}

# tap <label> [seconds] [exact|contains]
tap() {
  local centre
  centre="$(wait_for "$@")"
  log "tap \"$1\" at $centre"
  # shellcheck disable=SC2086
  adb shell input tap $centre
}

# maybe_tap <label> [seconds]: for a dialog that may or may not come up (a permission, a first-run note).
maybe_tap() {
  local centre
  if centre="$(wait_for "$1" "${2:-3}" "${3:-exact}" 2>/dev/null)"; then
    # shellcheck disable=SC2086
    adb shell input tap $centre
  fi
}

# A clean install's first launch: the app's data is cleared, so every recording starts from the sign-in screen.
fresh_start() {
  adb shell am force-stop "$APP"
  adb shell pm clear "$APP" >/dev/null
  adb shell am start -n "$APP/$ACTIVITY" >/dev/null
}

# record_start <name> / record_stop: one recording, $OUT/<name>.mp4. Android stops a recording at three minutes.
record_start() {
  RECORDING="$1"
  adb shell rm -f "/sdcard/$RECORDING.mp4"
  adb shell screenrecord --bit-rate 8000000 --time-limit 170 "/sdcard/$RECORDING.mp4" &
  RECORD_PID=$!
  sleep 1
}

record_stop() {
  # SIGINT, so the recorder writes the file's index before it goes; a killed recording does not play.
  adb shell pkill -INT screenrecord || true
  wait "$RECORD_PID" 2>/dev/null || true
  sleep 2
  adb pull "/sdcard/$RECORDING.mp4" "$OUT/$RECORDING.mp4" >/dev/null
  adb exec-out screencap -p >"$OUT/$RECORDING-last-frame.png" || true
  log "recorded $OUT/$RECORDING.mp4 ($(du -h "$OUT/$RECORDING.mp4" | cut -f1))"
}
