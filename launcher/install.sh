#!/usr/bin/env bash
# Installs dumbtv home on the TV and gives it the HOME button.
#   ./launcher/install.sh            download the release APK (checksum-verified), install, take over HOME
#   ./launcher/install.sh --build    build from source instead (needs a JDK and the Android SDK)
#   ./launcher/install.sh --undo     hand HOME back to Amazon's launcher
# Pick the device with ADB_SERIAL when more than one is connected.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# shellcheck disable=SC1091
source version.env
APK_URL=https://github.com/sodasoba1/dumbtv/releases/download/launcher-v$VERSION/dumbtv-home.apk
SHA_URL=$APK_URL.sha256

PKG=tv.dumbtv.home
ADB=${ADB:-adb}

adb_() { "$ADB" ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }
sh_() { adb_ shell "$@" </dev/null; }
installed() { sh_ pm path "$1" 2>/dev/null | grep -q '^package:'; }
# shellcheck disable=SC1091
source ../scripts/home.sh

mode=release
case ${1:-} in
  "") ;;
  --build) mode=build ;;
  --undo) mode=undo ;;
  *) echo "usage: $0 [--build | --undo]" >&2; exit 2 ;;
esac

command -v "$ADB" >/dev/null || { echo "error: adb not found — install Android platform-tools" >&2; exit 1; }
state=$(adb_ get-state 2>/dev/null || true)
[[ $state == device ]] || { echo "error: no TV connected (adb connect <tv-ip>:5555; set ADB_SERIAL if several)" >&2; exit 1; }
target_serial=$(adb_ get-serialno 2>/dev/null | tr -d '\r')

reconnect_after_vpn_change() {
  [[ $target_serial == *:* ]] || return 0
  for _ in $(seq 1 12); do
    "$ADB" connect "$target_serial" >/dev/null 2>&1 || true
    [[ $("$ADB" -s "$target_serial" get-state 2>/dev/null) == device ]] && return 0
    sleep 2
  done
  return 1
}

dev_sdk=$(sh_ getprop ro.build.version.sdk | tr -d '\r')
if [[ $dev_sdk != 25 ]]; then
  echo "error: this launcher APK is built only for Fire OS 6 / Android 7.1.2 (API 25); device reports API ${dev_sdk:-unknown}" >&2
  exit 1
fi

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

if [[ $mode == undo ]]; then
  # force-stop also keeps the Fire OS 6 Keeper from restarting at the next boot.
  sh_ am force-stop $PKG >/dev/null 2>&1 || true
  sh_ appops set $PKG GET_USAGE_STATS default >/dev/null 2>&1 || true
  sh_ appops set $PKG AUTO_START default >/dev/null 2>&1 || true
  sh_ appops write-settings >/dev/null 2>&1 || true
  sh_ pm revoke $PKG android.permission.READ_LOGS >/dev/null 2>&1 || true
  sh_ dumpsys deviceidle whitelist -$PKG >/dev/null 2>&1 || true
  sh_ am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
  echo "HOME handed back to Amazon's launcher (dumbtv home stays installed: adb uninstall $PKG)"
  exit 0
fi

build_apk() {
  ./build.sh
  APK=build/dumbtv-home.apk
}

# adb ends its output with a blank line, so take the line that carries the result.
install_apk() {
  out=$(adb_ install -r "$APK" 2>&1 | grep -m1 -E 'Success|Failure|failed' || true)
}

if [[ $mode == build ]]; then
  build_apk
else
  mkdir -p build
  APK=build/dumbtv-home-$VERSION.apk
  SUM=build/dumbtv-home-$VERSION.apk.sha256
  echo "checking fork release $VERSION"
  # The release workflow publishes a SHA-256 sidecar alongside the APK.
  curl -fL --retry 4 --retry-delay 3 --retry-all-errors --connect-timeout 20 \
    -o "$SUM.part" "$SHA_URL" || { echo "error: could not download checksum $SHA_URL" >&2; exit 1; }
  expected=$(tr -d '[:space:]' < "$SUM.part")
  [[ $expected =~ ^[0-9a-f]{64}$ ]] || { echo "error: invalid SHA-256 sidecar from $SHA_URL" >&2; exit 1; }
  if [[ ! -f $APK || $(sha256 "$APK") != "$expected" ]]; then
    echo "downloading dumbtv home $VERSION from sodasoba1/dumbtv"
    curl -fL --retry 4 --retry-delay 3 --retry-all-errors --connect-timeout 20 --progress-bar \
      -o "$APK.part" "$APK_URL" || { rm -f "$APK.part"; echo "error: could not download $APK_URL" >&2; exit 1; }
    mv "$APK.part" "$APK"
  fi
  got=$(sha256 "$APK")
  [[ $got == "$expected" ]] || { rm -f "$SUM.part"; echo "error: checksum mismatch for $APK (got $got, expected $expected) — not installing" >&2; exit 1; }
  mv "$SUM.part" "$SUM"
  echo "checksum ok"
fi

install_apk
case $out in
  *Success*) ;;
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
    echo "error: the installed dumbtv home was signed with a different key (release vs your own build)." >&2
    if [[ $mode == release ]]; then
      echo "  it is probably your own build: run ./launcher/install.sh --build instead" >&2
    else
      echo "  it is probably the release: this build's key cannot update it." >&2
    fi
    echo "  Replacing it means uninstalling it (adb uninstall $PKG), which deletes its settings;" >&2
    exit 1 ;;
  *) echo "error: adb install failed: $(printf '%s' "$out" | tail -1)" >&2; exit 1 ;;
esac
echo "installed $PKG"

sh_ dumpsys deviceidle whitelist +$PKG >/dev/null
# Fire OS 6 only: Keeper uses UsageStats and ActivityManager logcat; no accessibility service.
sh_ appops set $PKG GET_USAGE_STATS allow
# Request READ_LOGS on every install/update, then verify the resulting package
# permission state. Fire OS 6 does not reliably implement pm check-permission.
grant_error=""
sh_ pm grant $PKG android.permission.READ_LOGS >/dev/null 2>&1 || grant_error="pm grant returned a failure"
package_dump=$(sh_ dumpsys package $PKG 2>/dev/null || true)
if grep -Eq '^[[:space:]]*android\.permission\.READ_LOGS:[[:space:]]*granted=true([[:space:]]|$)' <<<"$package_dump"; then
  echo "logcat monitoring: READ_LOGS granted"
else
  if [[ -n $grant_error ]]; then
    echo "note: READ_LOGS could not be confirmed ($grant_error); UsageStats fallback remains enabled"
  else
    echo "note: READ_LOGS grant could not be verified; UsageStats fallback remains enabled"
  fi
fi
start_home $PKG/.Home
sleep 3
if ! grep -q 'isForeground=true' <<<"$(sh_ dumpsys activity services $PKG 2>/dev/null)"; then
  echo "error: the Fire OS 6 home keeper did not start ($PKG/.Keeper)" >&2
  exit 1
fi
front=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  front=$(sh_ dumpsys activity activities | grep -m1 'ResumedActivity: ' || true)
  [[ $front == *"$PKG"* ]] && break
  sleep 1
done
if [[ -z $front ]]; then
  echo "note: could not read which app is in front; ./scripts/verify.sh checks the Keeper"
elif [[ $front != *"$PKG"* ]]; then
  echo "error: dumbtv home is installed but not in front ($front)" >&2
  exit 1
fi
echo "home: dumbtv (Keeper, Fire OS 6 / API 25)"

sleep 2
printf 'front window:  %s\n' "$(sh_ dumpsys window | grep -m1 mCurrentFocus | sed 's/.* u0 //; s/}$//')"

# Retire the old optional local DNS filter. Fire OS 6 can forget the always-on
# setting at boot while personalDNSfilter's own receiver restarts its VPN, so
# disable the package whenever present. Clear the setting only if it names this
# app; other VPN apps are never changed. Keep this last because stopping a VPN
# can briefly interrupt a network ADB connection.
legacy_vpn=$(sh_ settings get secure always_on_vpn_app | tr -d '\r')
if installed dnsfilter.android; then
  echo "disabling the legacy personalDNSfilter app"
  disable_rc=0
  sh_ pm disable-user --user 0 dnsfilter.android >/dev/null 2>&1 || disable_rc=$?
  reconnect_after_vpn_change || {
    echo "error: ADB did not reconnect after disabling personalDNSfilter" >&2
    exit 1
  }
  sh_ pm list packages -d | tr -d '\r' | grep -qx 'package:dnsfilter.android' || {
    echo "error: personalDNSfilter is still enabled after the disable request (exit $disable_rc)" >&2
    exit 1
  }
  echo "removing legacy personalDNSfilter privileges"
  sh_ appops set dnsfilter.android ACTIVATE_VPN default >/dev/null 2>&1 || true
  sh_ appops set dnsfilter.android AUTO_START default >/dev/null 2>&1 || true
  sh_ dumpsys deviceidle whitelist -dnsfilter.android >/dev/null 2>&1 || true
  sh_ pm revoke dnsfilter.android android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
  sh_ pm revoke dnsfilter.android android.permission.WRITE_EXTERNAL_STORAGE >/dev/null 2>&1 || true
  echo "personalDNSfilter disabled; remove it from Settings > Applications if desired"
fi
if [[ $legacy_vpn == dnsfilter.android ]]; then
  echo "clearing the legacy personalDNSfilter always-on VPN selection"
  sh_ settings delete secure always_on_vpn_app >/dev/null
  remaining=$(sh_ settings get secure always_on_vpn_app | tr -d '\r')
  [[ $remaining != dnsfilter.android ]] || { echo "error: could not clear the legacy VPN selection" >&2; exit 1; }
fi
