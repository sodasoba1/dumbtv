#!/usr/bin/env bash
# Read-only. Prints what the TV is and what the full pass would change.
# Usage: ./scripts/preflight.sh [--dry-run]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
parse_flags "$@"
need_device
load_conf
mkdir -p "$REPORT"

step "device"
note "serial        $SERIAL"
note "manufacturer  $(dev1 getprop ro.product.manufacturer)"
note "model         $(dev1 getprop ro.product.model)"
note "build         $(dev1 getprop ro.build.display.id)"
note "android       $(dev1 getprop ro.build.version.release) (sdk $(dev1 getprop ro.build.version.sdk))"
note "kernel        $(dev1 uname -r)"
note "abi           $(dev1 getprop ro.product.cpu.abi)"
note "verified boot $(dev1 getprop ro.boot.verifiedbootstate)  (1 = locked, cannot be changed)"
note "gateway       $(gateway)"

if is_fireos; then
  note "platform      $(fireos_name)"
  note "lists         ${DATA#$ROOT/}/$(basename "$(dirname "$(fos packages.txt)")")"
  if [[ $SDK -lt 28 ]]; then
    note "fire os 6     own package lists, no RUN_ANY_IN_BACKGROUND, and HOME is held by the"
    note "              launcher's HomeSentinel because 6.7 refuses accessibility services"
  fi
else
  note "platform      NOT Amazon hardware — read docs/OTHER-TVS.md before running debloat.sh"
fi

step "current state"
note "packages      $(dev 'pm list packages' | wc -l | tr -d ' ') installed, $(dev 'pm list packages -d' | wc -l | tr -d ' ') disabled"
note "third-party   $(dev 'pm list packages -3' | sed 's/package://' | grep -vc overlay) apps"
note "home screen   $(front_activity | awk -F/ '{print $1}')"
note "free ram      $(dev 'dumpsys meminfo' | sed -n 's/.*Free RAM: *\([0-9,]*K\).*/\1/p' | head -1)"
note "launcher      $(present tv.dumbtv.home && echo 'dumbtv home installed' || echo 'not installed')"

step "the pass would"
note "disable $(cleanup_list | wc -l | tr -d ' ') Amazon packages, minus anything in never-disable.txt"
# debloat.sh removes only the apps named in dumbtv.conf, never the whole user-apps.txt.
if [[ -n $REMOVE_EXTRA ]]; then note "remove only the apps you named: $REMOVE_EXTRA"
else note "remove none of your apps (none named in dumbtv.conf)"; fi
note "deny background work + data to $(list_of "$(fos protected.txt)" | wc -l | tr -d ' ') protected packages"
note "optional DNS filtering is external; see docs/PI-HOLE.md"
note "apply $(list_of "$(fos settings.txt)" | wc -l | tr -d ' ') privacy settings"
if [[ $LAUNCHER == 1 ]]; then
  note "replace the home screen with dumbtv home (this repo's own launcher, no internet access)"
fi
note "reboot twice (about 5 minutes)"

step "not touched, ever"
note "/system (dm-verity), the bootloader, the launcher package itself, settings, TV input, device control, Bluetooth pairing"
note "see ${DATA#$ROOT/}/$(basename "$(dirname "$(fos never-disable.txt)")")/never-disable.txt"

step "snapshot before anything changes"
if [[ $DRY_RUN == 0 ]]; then
  ensure_report_device
  secure_snapshot="$REPORT/settings-before-secure.txt"
  global_snapshot="$REPORT/settings-before-global.txt"
  if [[ -f $secure_snapshot && -f $global_snapshot ]]; then
    note "settings snapshot already exists, keeping the original"
  elif [[ -e $secure_snapshot || -e $global_snapshot ]]; then
    die "incomplete settings snapshot in $REPORT; refusing to overwrite the original baseline"
  else
    secure_values=$(dev 'settings list secure') || die "could not read secure settings; no snapshot was saved"
    global_values=$(dev 'settings list global') || die "could not read global settings; no snapshot was saved"
    [[ -n $secure_values && -n $global_values ]] ||
      die "settings snapshot was unexpectedly empty; refusing to save an incomplete baseline"
    printf '%s\\n' "$secure_values" > "$secure_snapshot.tmp" ||
      die "could not write secure settings snapshot"
    printf '%s\\n' "$global_values" > "$global_snapshot.tmp" ||
      die "could not write global settings snapshot"
    mv "$secure_snapshot.tmp" "$secure_snapshot" &&
      mv "$global_snapshot.tmp" "$global_snapshot" ||
      die "could not publish complete settings snapshots"
    snapshot_package_states
    note "saved device-scoped settings and package snapshots in $REPORT"
  fi
fi
if [[ $DRY_RUN == 0 && ! -f $REPORT/packages-before.txt ]]; then
  snapshot_package_states
fi

step "next"
note "bash ./scripts/dns-preflight.sh  DNS and blocklist audit (read-only, before install)"
note "./scripts/all.sh            full pass (asks first)"
note "./scripts/debloat.sh --dry-run   see the exact package commands"
