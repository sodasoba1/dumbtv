#!/usr/bin/env bash
# The whole pass in the right order. Reboots the TV twice, ~5 minutes.
# Usage: ./scripts/all.sh [--dry-run] [-y]
#
# It asks what to keep before touching anything, unless dumbtv.conf already
# exists (then the saved answers are reused) or -y was passed (defaults).
set -uo pipefail
DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
FLAGS=("$@")
# Same default as lib.sh, and a CONF from the caller wins there too.
CONF=${CONF:-$DIR/../dumbtv.conf}

# bash 3.2 (macOS) errors on "${FLAGS[@]}" when the array is empty under set -u.
run() { "$@" ${FLAGS[@]+"${FLAGS[@]}"}; }

run "$DIR/preflight.sh" || exit 1
printf '\n'

if [[ ! -f $CONF ]]; then
  run "$DIR/configure.sh" || exit 1
fi

# configure.sh runs in a child process, so read its saved opt-in here too.
# Parse only the YOUTUBE scalar; never source the household config as shell code.
YOUTUBE=0
if [[ -f $CONF ]]; then
  while IFS='=' read -r key value; do
    [[ $key == YOUTUBE ]] || continue
    value=${value%\"}; value=${value#\"}
    case $value in 0|1) YOUTUBE=$value ;; *) echo "error: YOUTUBE must be 0 or 1 in $CONF" >&2; exit 1 ;; esac
  done < "$CONF"
fi

run bash "$DIR/cleanup-legacy-dnsfilter.sh" || exit 1
run "$DIR/debloat.sh"   || exit 1
run "$DIR/lockdown.sh"  || exit 1
# The launcher APK and HomeSentinel are intentionally API 25 only. Keep the
# rest of the cleanup usable on other Fire OS generations; direct launcher.sh
# and launcher/install.sh invocations still reject unsupported SDKs.
source "$DIR/lib.sh"
need_device
if [[ $SDK == 25 || ${LAUNCHER:-1} == 0 ]]; then
  run "$DIR/launcher.sh" || exit 1
else
  printf 'skip launcher: HomeSentinel supports API 25 only (device reports API %s)\n' "$SDK"
fi
# The configuration interview already captured explicit consent to replace YouTube.
# Do not ask a second time in the middle of the full install.
if [[ ${YOUTUBE:-0} == 1 ]]; then ASSUME_YES=1 run "$DIR/youtube.sh" || exit 1; fi
"$DIR/verify.sh" || exit $?

exit 0
