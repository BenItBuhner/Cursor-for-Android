#!/usr/bin/env bash
# A computer of the account's coming online while New Chat is open: the device list finds it, New Chat offers it under
# the composer by itself, and "Run here" moves the chat onto it. The demo account's second computer comes online a
# quarter of a minute into the session.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

fresh_start
wait_for "Continue with Cursor" 120 >/dev/null
tap "Try the demo" 30
started=$SECONDS
maybe_tap "Allow" 10
maybe_tap "Continue" 6
maybe_tap "Allow" 4
wait_for "Cloud" 60 >/dev/null
record_start machine-offer
sleep 2
# The computer is not up yet: the list has only the busy one.
tap "Cloud"
wait_for "Refresh devices" 20 >/dev/null
sleep 2
while ((SECONDS - started < 18)); do sleep 1; done
tap "Refresh devices"
sleep 2
# The account's computers are under Remote: the one that has just come online is listed there now.
tap "Remote"
wait_for "Bennetts-MacBook-Pro" 20 contains >/dev/null
sleep 2
# Back out of Remote, then out of the device list.
for _ in 1 2 3; do
  adb shell input keyevent KEYCODE_BACK
  sleep 1.5
  wait_for "Refresh devices" 2 >/dev/null 2>&1 || break
done
wait_for "Run here" 20 >/dev/null
sleep 3
tap "Run here"
sleep 4
record_stop
