#!/usr/bin/env bash
# The HOME takeover helpers of launcher/install.sh. They use the caller's sh_ (adb shell),
# and set_futurity also its dev_sdk.

# Starts dumbtv home at the end of the install.
#
# `am start -W` waits until the activity has drawn, and a sleeping TV never draws it, so
# an install that ran while the TV fell asleep hung for ever. The TV is woken first (a
# no-op when it is awake), and the wait is bounded, $2 seconds (20 by default).
start_home() {
  local pid i bound=${2:-20}
  sh_ input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  sh_ am start -W -n "$1" >/dev/null 2>&1 &
  pid=$!
  for ((i = 0; i < bound; i++)); do
    if ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid" 2>/dev/null || true
      return 0
    fi
    sleep 1
  done
  kill "$pid" 2>/dev/null || true
  echo "note: dumbtv home did not draw within $bound s; is the TV asleep? Press a button on the remote."
  return 0
}

