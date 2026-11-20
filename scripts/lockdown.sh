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
  snapshot_package_states
  # Preserve the exact AppOps modes and whether each UID was already blacklisted.
  # Never overwrite the first snapshot on a subsequent run.
  snapshot="$REPORT/lockdown-before.tsv"
  data_saver_snapshot="$REPORT/data-saver-before.txt"
  if [[ -e $snapshot && ! -e $data_saver_snapshot
        || ! -e $snapshot && -e $data_saver_snapshot ]]; then
    die "incomplete lockdown snapshot: AppOps and Data Saver baselines must both exist; refusing to reconstruct original state"
  fi
  if [[ ! -e $snapshot ]]; then
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
        sed -n 's/.*RUN_IN_BACKGROUND:[[:space:]]*\([^,[:space:]]*\).*/\1/p' | head -1)
      [[ -n $bg ]] || { rm -f "$tmp"; die "could not snapshot RUN_IN_BACKGROUND for $p"; }
      any=not-applicable
      if [[ $SDK -ge 28 ]]; then
        any=$(dev "appops get $p RUN_ANY_IN_BACKGROUND" |
          sed -n 's/.*RUN_ANY_IN_BACKGROUND:[[:space:]]*\([^,[:space:]]*\).*/\1/p' | head -1)
        [[ -n $any ]] || { rm -f "$tmp"; die "could not snapshot RUN_ANY_IN_BACKGROUND for $p"; }
      fi
      was_blacklisted=0
      grep -Eq "(^|[^0-9])$uid([^0-9]|$)" <<<"$blacklist" && was_blacklisted=1
      printf '%s\t%s\t%s\t%s\t%s\n' "$p" "$uid" "$bg" "$any" "$was_blacklisted" >> "$tmp"
    done
    mv "$tmp" "$snapshot" || die "could not publish AppOps/network-policy snapshot"
  fi
  if [[ ! -e $data_saver_snapshot ]]; then
    data_saver=$(dev 'cmd netpolicy get restrict-background') || {
      rm -f "$snapshot"
      die "could not snapshot the original Data Saver state"
    }
    case $data_saver in
      *enabled*) printf 'enabled\n' > "$data_saver_snapshot.tmp" ;;
      *disabled*) printf 'disabled\n' > "$data_saver_snapshot.tmp" ;;
      *) rm -f "$snapshot"; die "could not interpret the original Data Saver state: $data_saver" ;;
    esac
    mv "$data_saver_snapshot.tmp" "$data_saver_snapshot" || {
      rm -f "$snapshot"
      die "could not publish Data Saver snapshot"
    }
  fi

  # Record ownership before the first policy mutation. A restore can then recover
  # a partially applied lockdown, while preflight-only snapshots remain untouched.
  lockdown_state="$REPORT/lockdown-state.txt"
  if [[ -f $lockdown_state ]]; then
    prior_state=$(tr -d '\r\n' < "$lockdown_state")
    case $prior_state in
      started|complete) ;;
      *) die "invalid lockdown execution marker: $prior_state" ;;
    esac
  fi
  printf 'started\n' > "$lockdown_state.tmp" ||
    die "could not create lockdown execution marker"
  mv "$lockdown_state.tmp" "$lockdown_state" ||
    die "could not publish lockdown execution marker"
fi

acmd cmd netpolicy set restrict-background true >/dev/null
if [[ $DRY_RUN == 0 ]]; then
  data_saver_now=$(dev 'cmd netpolicy get restrict-background' | tr -d '\r')
  case $data_saver_now in *enabled*) ;; *) die "Data Saver did not enable (got: $data_saver_now)" ;; esac
fi

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
  if [[ $DRY_RUN == 0 ]]; then
    op=RUN_IN_BACKGROUND
    if [[ $SDK -ge 28 ]]; then
      actual_any=$(dev1 appops get "$p" RUN_ANY_IN_BACKGROUND |
        sed -n 's/.*RUN_ANY_IN_BACKGROUND:[[:space:]]*\([^,[:space:]]*\).*/\1/p')
      [[ $actual_any == ignore ]] ||
        die "RUN_ANY_IN_BACKGROUND restriction did not verify for $p"
      op=RUN_IN_BACKGROUND
    fi
    actual_bg=$(dev1 appops get "$p" "$op" |
      sed -n "s/.*$op:[[:space:]]*\([^,[:space:]]*\).*/\1/p")
    [[ $actual_bg == ignore ]] || die "$op restriction did not verify for $p"
    blacklist_now=$(dev 'cmd netpolicy list restrict-background-blacklist')
    grep -Eq "(^|[^0-9])$uid([^0-9]|$)" <<<"$blacklist_now" ||
      die "metered-data blacklist did not verify for $p (uid $uid)"
  fi
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
  printf 'complete\n' > "$REPORT/lockdown-state.txt.tmp" ||
    die "could not mark lockdown as complete"
  mv "$REPORT/lockdown-state.txt.tmp" "$REPORT/lockdown-state.txt" ||
    die "could not publish completed lockdown marker"
fi
