#!/usr/bin/env bash
# Installs dumbtv home on Fire OS 6 / Android 7.1.2 (API 25) only.
# Keeper restores the launcher after Amazon Home becomes foreground. Usage: ./scripts/launcher.sh [--dry-run] [-y]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

LOCAL_APK=""
PERMISSIONS_ONLY=0
flags=()
while [[ $# -gt 0 ]]; do
  case $1 in
    --apk)
      [[ $# -ge 2 && -n $2 ]] || die "--apk requires a file path"
      [[ -z $LOCAL_APK && $PERMISSIONS_ONLY == 0 ]] || die "--apk and --permissions-only cannot be combined"
      LOCAL_APK=$2
      shift 2 ;;
    --permissions-only)
      [[ -z $LOCAL_APK && $PERMISSIONS_ONLY == 0 ]] || die "--apk and --permissions-only cannot be combined"
      PERMISSIONS_ONLY=1
      shift ;;
    *)
      flags+=("$1")
      shift ;;
  esac
done
parse_flags "${flags[@]+"\${flags[@]}"}"
need_device
load_conf

if [[ $LAUNCHER == 0 && -z $LOCAL_APK && $PERMISSIONS_ONLY == 0 ]]; then
  note "LAUNCHER=0 in dumbtv.conf — keeping Amazon's home screen. Re-run ./scripts/configure.sh to change that."
  exit 0
fi

[[ $SDK == 25 ]] || die "dumbtv home supports only Fire OS 6 / Android 7.1.2 (API 25); this device reports API $SDK"

PKG=tv.dumbtv.home

step "plan"
if [[ -n $LOCAL_APK ]]; then
  [[ -s $LOCAL_APK ]] || die "APK file not found or empty: $LOCAL_APK"
  note "install supplied DumbTV APK: $LOCAL_APK"
elif [[ $PERMISSIONS_ONLY == 1 ]]; then
  note "reapply and verify Keeper permissions without reinstalling the APK"
else
  note "install dumbtv home: open source, no internet permission, no ad ID"
  note "the prebuilt APK comes from this repo's GitHub release and is checksum-verified"
fi
note "Keeper requires UsageStats, READ_LOGS and (where supported) AUTO_START access"
note "Amazon's launcher stays installed: hold HOME for Fire OS's menu, or Options > Open Amazon's home screen"
if [[ $DRY_RUN == 1 ]]; then
  if [[ -n $LOCAL_APK ]]; then
    note "[dry-run] ADB=$ADB ADB_SERIAL=$SERIAL launcher/install.sh --apk $LOCAL_APK"
  elif [[ $PERMISSIONS_ONLY == 1 ]]; then
    note "[dry-run] ADB=$ADB ADB_SERIAL=$SERIAL launcher/install.sh --permissions-only"
  else
    note "[dry-run] ADB=$ADB ADB_SERIAL=$SERIAL launcher/install.sh"
  fi
  exit 0
fi
if [[ $PERMISSIONS_ONLY == 1 ]]; then
  confirm "Reapply Keeper permissions for dumbtv home on $SERIAL?" || die "cancelled"
elif [[ -n $LOCAL_APK ]]; then
  confirm "Install/update dumbtv home from this APK on $SERIAL?" || die "cancelled"
else
  confirm "Replace the home screen on $SERIAL?" || die "cancelled"
fi

if [[ -n $LOCAL_APK ]]; then
  ADB=$ADB ADB_SERIAL=$SERIAL "$ROOT/launcher/install.sh" --apk "$LOCAL_APK" || die "launcher APK install failed (see above)"
elif [[ $PERMISSIONS_ONLY == 1 ]]; then
  ADB=$ADB ADB_SERIAL=$SERIAL "$ROOT/launcher/install.sh" --permissions-only || die "Keeper permission repair failed (see above)"
else
  ADB=$ADB ADB_SERIAL=$SERIAL "$ROOT/launcher/install.sh" || die "launcher install failed (see above)"
fi

for _ in 1 2 3 4 5 6 7 8 9 10; do
  sleep 1
  home=$(front_activity)
  [[ $home == "$PKG"/* ]] && break
done
if [[ $home == "$PKG"/* ]]; then
  note "home screen is dumbtv home"
elif [[ -z $home ]]; then
  note "could not read which app is in front; ./scripts/verify.sh checks the takeover"
elif [[ $home == com.amazon.tv.parentalcontrols/* ]]; then
  note "Fire OS is asking for the parental-controls PIN ($home)"
  note "enter it on the TV, then run this script again"
  exit 1
else
  note "home screen is $home"
  note "press HOME once or run this script again; see docs/TROUBLESHOOTING.md if it will not stick"
  exit 1
fi
