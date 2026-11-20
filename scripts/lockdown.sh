#!/usr/bin/env bash
# Takes away background execution from the packages Amazon refuses to disable, and
# their background data on metered networks. Data Saver leaves Wi-Fi and Ethernet
# alone, so on those the DNS filter is what stops their uploads.
# Usage: ./scripts/lockdown.sh [--dry-run] [-y]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
parse_flags "$@"
need_device
need_supported_fireos
load_conf

step "network policy"
note "data saver on, then a blacklist entry per protected package"

if [[ $DRY_RUN == 0 ]]; then
  ensure_report_device
  # Preserve the exact AppOps modes and whether each UID was already blacklisted.
  # Never overwrite the first snapshot on a subsequent run.
  snapshot="$REPORT/lockdown-before.tsv"
  if [[ ! -f $snapshot ]]; then
    blacklist=$(dev 'cmd netpolicy list restrict-background-blacklist') ||
      die "could not read the existing background-data blacklist"
    tmp="$snapshot.tmp"
    : > "$tmp"
    for p in $(list_of "$(fos protected.txt)"); do
      keep_pkg "$p" && continue
      present "$p" || continue
      uid=$(uid_of "$p")
      [[ -n $uid && $uid -ge 10000 ]] || continue
      bg=$(dev "appops get $p RUN_IN_BACKGROUND" |
        sed -n 's/.*RUN_IN_BACKGROUND:[[:space:]]*\\([^,[:space:]]*\\).*/\\1/p' | head -1)
      [[ -n $bg ]] || { rm -f "$tmp"; die "could not snapshot RUN_IN_BACKGROUND for $p"; }
      any=not-applicable
      if [[ $SDK -ge 28 ]]; then
        any=$(dev "appops get $p RUN_ANY_IN_BACKGROUND" |
          sed -n 's/.*RUN_ANY_IN_BACKGROUND:[[:space:]]*\\([^,[:space:]]*\\).*/\\1/p' | head -1)
        [[ -n $any ]] || { rm -f "$tmp"; die "could not snapshot RUN_ANY_IN_BACKGROUND for $p"; }
      fi
      was_blacklisted=0
      grep -Eq "(^|[^0-9])$uid([^0-9]|$)" <<<"$blacklist" && was_blacklisted=1
      printf '%s\\t%s\\t%s\\t%s\\t%s\\n' "$p" "$uid" "$bg" "$any" "$was_blacklisted" >> "$tmp"
    done
    mv "$tmp" "$snapshot" || die "could not publish AppOps/network-policy snapshot"
  fi
  if [[ ! -f $REPORT/data-saver-before.txt ]]; then
    data_saver=$(dev 'cmd netpolicy get restrict-background') ||
      die "could not snapshot the original Data Saver state"
    case $data_saver in
      *enabled*) printf 'enabled\\n' > "$REPORT/data-saver-before.txt.tmp" ;;
      *disabled*) printf 'disabled\\n' > "$REPORT/data-saver-before.txt.tmp" ;;
      *) die "could not interpret the original Data Saver state: $data_saver" ;;
    esac
    mv "$REPORT/data-saver-before.txt.tmp" "$REPORT/data-saver-before.txt" ||
      die "could not publish Data Saver snapshot"
  fi
fi

acmd cmd netpolicy set restrict-background true >/dev/null

for p in $(list_of "$(fos protected.txt)"); do
  if keep_pkg "$p"; then
    printf '  %-46s SKIP (keep list)\n' "$p"; continue
  fi
  if ! present "$p"; then
    printf '  %-46s absent\n' "$p"; continue
  fi
  uid=$(uid_of "$p")
  if [[ -z $uid || $uid -lt 10000 ]]; then
    printf '  %-46s SKIP (shared system uid %s)\n' "$p" "${uid:-?}"
    continue
  fi
  bg_off "$p"
  acmd am force-stop "$p" >/dev/null 2>&1
  acmd cmd netpolicy add restrict-background-blacklist "$uid" >/dev/null
  printf '  %-46s ops off, metered data blocked (uid %s)\n' "$p" "$uid"
done

step "verify"
if [[ $DRY_RUN == 1 ]]; then
  note "skipped in dry-run"
else
  note "data saver: $(dev 'cmd netpolicy get restrict-background' | tr -d '\r')"
  note "blacklist:  $(dev 'cmd netpolicy list restrict-background-blacklist' | tr -d '\r')"
  op=RUN_ANY_IN_BACKGROUND; [[ $SDK -lt 28 ]] && op=RUN_IN_BACKGROUND
  for p in $(list_of "$(fos protected.txt)"); do
    if present "$p"; then note "$(printf '%-44s' "$p") $(dev "appops get $p $op" | tr -d '\r')"; fi
  done
fi
