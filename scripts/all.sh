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

# configure.sh runs in a child process, so load its saved opt-in here too.
# A dry-run may not have written a config; absent YOUTUBE still defaults to 0.
if [[ -f $CONF ]]; then
  source "$CONF" || exit 1
fi

run bash "$DIR/cleanup-legacy-dnsfilter.sh" || exit 1
run "$DIR/debloat.sh"   || exit 1
run "$DIR/lockdown.sh"  || exit 1
run "$DIR/launcher.sh"  || exit 1
if [[ ${YOUTUBE:-0} == 1 ]]; then run "$DIR/youtube.sh" || exit 1; fi
"$DIR/verify.sh" || exit $?

# DNS filtering is external and optional. Offer a live check only in an
# interactive install; -y remains unattended and never prompts for a Pi-hole.
case " ${FLAGS[*]} " in
  *" --dry-run "*) ;;
  *" -y "*|*" --yes "*) ;;
  *)
    printf '\n'
    printf 'Pi-hole IP for a live DNS test (Enter to skip): '
    read -r pihole_ip
    if [[ -n $pihole_ip ]]; then
      bash "$DIR/dns-check.sh" "$pihole_ip"
      dns_rc=$?
      if [[ $dns_rc != 0 ]]; then
        printf '\nDNS verification reported a problem. The DumbTV install is complete; see docs/PI-HOLE.md.\n'
      fi
    fi
    ;;
esac
exit 0
