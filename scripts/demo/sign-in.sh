#!/usr/bin/env bash
# The sign-in screen arriving on a clean install, the API-key row opening, and the way into the demo account.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

adb shell am force-stop "$APP"
adb shell pm clear "$APP" >/dev/null
record_start sign-in
adb shell am start -n "$APP/$ACTIVITY" >/dev/null
wait_for "Continue with Cursor" 60 >/dev/null
sleep 2
tap "Use an API key instead"
sleep 2
tap "Use an API key instead"
sleep 1
tap "Try the demo"
sleep 6
record_stop
