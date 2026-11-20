#!/usr/bin/env bash
# Installs dumbtv home on the TV and gives it the HOME button.
#   ./launcher/install.sh            download the release APK (checksum-verified), install, take over HOME
#   ./launcher/install.sh --build    build from source instead (needs a JDK and the Android SDK)
#   ./launcher/install.sh --apk FILE install a supplied DumbTV APK, then apply HomeSentinel permissions
#   ./launcher/install.sh --permissions-only reapply HomeSentinel permissions without reinstalling
#   ./launcher/install.sh --undo     hand HOME back to Amazon's launcher
# Pick the device with ADB_SERIAL when more than one is connected.
set -euo pipefail
CALLER_DIR=$PWD
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
LOCAL_APK=""
case ${1:-} in
  "") ;;
  --build)
    [[ $# == 1 ]] || { echo "usage: $0 [--build | --apk FILE | --permissions-only | --undo]" >&2; exit 2; }
    mode=build ;;
  --apk)
    [[ $# == 2 && -n ${2:-} ]] || { echo "usage: $0 --apk FILE" >&2; exit 2; }
    mode=apk
    LOCAL_APK=$2
    [[ $LOCAL_APK == /* ]] || LOCAL_APK="$CALLER_DIR/$LOCAL_APK" ;;
  --permissions-only)
    [[ $# == 1 ]] || { echo "usage: $0 --permissions-only" >&2; exit 2; }
    mode=permissions ;;
  --undo)
    [[ $# == 1 ]] || { echo "usage: $0 --undo" >&2; exit 2; }
    mode=undo ;;
  *) echo "usage: $0 [--build | --apk FILE | --permissions-only | --undo]" >&2; exit 2 ;;
esac

command -v "$ADB" >/dev/null || { echo "error: adb not found — install Android platform-tools" >&2; exit 1; }
state=$(adb_ get-state 2>/dev/null || true)
[[ $state == device ]] || { echo "error: no TV connected (adb connect <tv-ip>:5555; set ADB_SERIAL if several)" >&2; exit 1; }
target_serial=$(adb_ get-serialno 2>/dev/null | tr -d '\r')
# Reuse the shared activity parser. It handles Android 7.1's mResumedActivity output.
# front_activity() uses dev(), so give it the serial selected above explicitly.
# shellcheck disable=SC1091
source ../scripts/lib.sh
SERIAL=$target_serial

reconnect_after_vpn_change() {
  [[ $target_serial == *:* ]] || return 0
  for _ in $(seq 1 12); do
    "$ADB" connect "$target_serial" >/dev/null 2>&1 || true
    [[ $("$ADB" -s "$target_serial" get-state 2>/dev/null) == device ]] && return 0
    sleep 2
  done
  return 1
}

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

if [[ $mode == undo ]]; then
  # Fail closed first: a package-replaced or sticky service restart must not reclaim HOME.
  sh_ settings put global dumbtv_homesentinel_enabled 0 >/dev/null ||
    { echo "error: could not suspend HomeSentinel takeover" >&2; exit 1; }
  sh_ am force-stop "$PKG" >/dev/null ||
    { echo "error: could not stop DumbTV; Amazon HOME handoff not verified" >&2; exit 1; }
  sh_ appops set "$PKG" GET_USAGE_STATS default >/dev/null ||
    { echo "error: could not revoke HomeSentinel UsageStats access" >&2; exit 1; }
  sh_ pm revoke "$PKG" android.permission.READ_LOGS >/dev/null ||
    { echo "error: could not revoke HomeSentinel log access" >&2; exit 1; }
  sh_ dumpsys deviceidle whitelist -"$PKG" >/dev/null ||
    { echo "error: could not remove DumbTV from the Doze whitelist" >&2; exit 1; }
  services=$(sh_ dumpsys activity services "$PKG" 2>/dev/null || true)
  if grep -q 'HomeSentinel' <<<"$services"; then
    echo "error: HomeSentinel is still running after force-stop; refusing to claim HOME was handed back" >&2
    exit 1
  fi
  sh_ am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -p com.amazon.tv.launcher >/dev/null ||
    { echo "error: Amazon HOME launch request failed" >&2; exit 1; }
  front=$(front_activity)
  if [[ $front != com.amazon.tv.launcher/com.amazon.tv.launcher.ui.HomeActivity_vNext ]]; then
    echo "error: Amazon HOME handoff not verified; foreground is ${front:-unknown}" >&2
    exit 1
  fi
  echo "HOME handed back and verified on Amazon's launcher (dumbtv home stays installed: adb uninstall $PKG)"
  exit 0
fi

# Only installation/build is restricted to the AFTMM API 25 target. Undo must
# remain available on any device carrying an older installation so HOME can
# always be handed back to Amazon, including during restore on other Fire OS versions.
dev_sdk=$(sh_ getprop ro.build.version.sdk | tr -d '\r')
if [[ $dev_sdk != 25 ]]; then
  echo "error: this launcher APK is built only for Fire OS 6 / Android 7.1.2 (API 25); device reports API ${dev_sdk:-unknown}" >&2
  exit 1
fi


build_apk() {
  ./build.sh
  APK=build/dumbtv-home.apk
}

# adb ends its output with a blank line, so take the line that carries the result.
install_apk() {
  out=$(adb_ install -r "$APK" 2>&1 | grep -m1 -E 'Success|Failure|failed' || true)
}

verify_installed_apk() {
  local device_apk tmp
  device_apk=$(sh_ pm path "$PKG" | sed -n 's/^package://p' | head -1)
  [[ -n $device_apk ]] || { echo "error: could not locate the installed $PKG APK for signer verification" >&2; return 1; }
  tmp=$(mktemp "${TMPDIR:-/tmp}/dumbtv-installed.XXXXXX") || return 1
  if ! adb_ pull "$device_apk" "$tmp" >/dev/null 2>&1; then
    rm -f "$tmp"
    echo "error: could not retrieve the installed APK for signer verification" >&2
    return 1
  fi
  ./verify-certificate.sh "$tmp"
  local rc=$?
  rm -f "$tmp"
  return $rc
}

if [[ $mode == permissions ]]; then
  installed "$PKG" || { echo "error: $PKG is not installed; install an APK first" >&2; exit 1; }
  verify_installed_apk || { echo "error: installed DumbTV signer is not trusted; no privileged permissions were changed" >&2; exit 1; }
  echo "reapplying HomeSentinel permissions for the verified $PKG"
elif [[ $mode == build ]]; then
  build_apk
elif [[ $mode == apk ]]; then
  [[ -s $LOCAL_APK ]] || { echo "error: APK file not found or empty: $LOCAL_APK" >&2; exit 1; }
  APK=$LOCAL_APK
  ./verify-certificate.sh "$APK" ||
    { echo "error: supplied APK signer is not trusted; not installing or granting HomeSentinel privileges" >&2; exit 1; }
  echo "installing supplied APK: $APK"
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
  # Verify the complete APK's v2/v3-aware signer identity against the committed pin.
  # This is a hard gate and runs before adb install; it never removes an installed APK.
  ./verify-certificate.sh "$APK"
  mv "$SUM.part" "$SUM"
  echo "checksum and release signer ok"
fi

# Suspend takeover only once the APK is ready to be installed. If download or
# build fails, the currently working launcher remains active; after this point
# every failure leaves the replacement fail-closed until a complete health check.
sh_ settings put global dumbtv_homesentinel_enabled 0 >/dev/null ||
  { echo "error: could not suspend HomeSentinel takeover before installation" >&2; exit 1; }
TAKEOVER_SUSPENDED=1

handoff_after_failure() {
  local rc=$?
  if [[ $rc != 0 && ${TAKEOVER_SUSPENDED:-0} == 1 ]]; then
    echo "warning: installation did not complete; keeping automatic takeover disabled and returning to Amazon Home" >&2
    sh_ settings put global dumbtv_homesentinel_enabled 0 >/dev/null 2>&1 || true
    sh_ am force-stop "$PKG" >/dev/null 2>&1 || true
    sh_ am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -p com.amazon.tv.launcher >/dev/null 2>&1 || true
    fallback_front=$(front_activity 2>/dev/null || true)
    if [[ $fallback_front == com.amazon.tv.launcher/* ]]; then
      echo "Amazon Home is foreground; automatic DumbTV takeover remains suspended" >&2
    else
      echo "warning: Amazon Home foreground could not be verified (${fallback_front:-unknown}); use the Fire TV Home button or run --undo again" >&2
    fi
  fi
  exit "$rc"
}
trap handoff_after_failure EXIT

if [[ $mode != permissions ]]; then
  install_apk
  case $out in
    *Success*) ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      echo "error: the installed dumbtv home was signed with a different key." >&2
      if [[ $mode == release ]]; then
        echo "  the installed app may be a local build; use ./launcher/install.sh --build only if it has the same signing key." >&2
      elif [[ $mode == apk ]]; then
        echo "  the supplied APK must use the same signing key as the installed app." >&2
      else
        echo "  the installed app may be the release; this build's key cannot update it." >&2
      fi
      echo "  Replacing it requires uninstalling $PKG, which deletes its settings." >&2
      exit 1 ;;
    *) echo "error: adb install failed: $(printf '%s' "$out" | tail -1)" >&2; exit 1 ;;
  esac
  installed "$PKG" || { echo "error: ADB reported success but $PKG is not installed" >&2; exit 1; }
  echo "installed $PKG"
fi

sh_ dumpsys deviceidle whitelist +$PKG >/dev/null
# Fire OS 6 only: HomeSentinel uses UsageStats and ActivityManager logcat; no accessibility service.
if ! sh_ appops set $PKG GET_USAGE_STATS allow; then
  echo "error: could not grant HomeSentinel UsageStats access" >&2
  exit 1
fi
usage_access=$(sh_ appops get $PKG GET_USAGE_STATS 2>/dev/null | tr -d '\r')
if ! grep -Eq 'GET_USAGE_STATS:[[:space:]]*allow([,[:space:]]|$)' <<<"$usage_access"; then
  echo "error: HomeSentinel UsageStats access is not enabled ($usage_access)" >&2
  exit 1
fi
# Request READ_LOGS on every install/update, then verify the resulting package
# permission state. Fire OS 6 does not reliably implement pm check-permission.
if ! sh_ pm grant $PKG android.permission.READ_LOGS; then
  echo "error: could not grant HomeSentinel READ_LOGS; ActivityManager logcat monitoring is required" >&2
  exit 1
fi
package_dump=$(sh_ dumpsys package $PKG 2>/dev/null || true)
if ! grep -Eq '^[[:space:]]*android\.permission\.READ_LOGS:[[:space:]]*granted=true([,[:space:]]|$)' <<<"$package_dump"; then
  echo "error: HomeSentinel READ_LOGS grant was not verified in the installed package ($PKG)" >&2
  exit 1
fi
echo "logcat monitoring: READ_LOGS granted and verified"
# --permissions-only does not reinstall the APK, so explicitly recreate the app
# process after the verified grant; HomeSentinel's logcat reader starts in onCreate.
if [[ $mode == permissions ]]; then
  sh_ am force-stop "$PKG" || { echo "error: could not restart $PKG after permission repair" >&2; exit 1; }
fi
start_home $PKG/.Home
sleep 3
if ! grep -q 'isForeground=true' <<<"$(sh_ dumpsys activity services $PKG 2>/dev/null)"; then
  echo "error: the Fire OS 6 HomeSentinel did not start ($PKG/.HomeSentinel)" >&2
  exit 1
fi
front=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  front=$(front_activity)
  [[ $front == "$PKG/"* ]] && break
  sleep 1
done
if [[ -z $front ]]; then
  echo "error: could not determine the foreground activity after starting dumbtv home" >&2
  exit 1
fi
if [[ $front != "$PKG/"* ]]; then
  echo "error: dumbtv home is installed but not in front ($front)" >&2
  exit 1
fi
echo "home: dumbtv (HomeSentinel, Fire OS 6 / API 25)"

sleep 2
printf 'front window:  %s\n' "$(sh_ dumpsys window | grep -m1 mCurrentFocus | sed 's/.* u0 //; s/}$//')"

# Retire the old optional local DNS filter. Fire OS 6 can forget the always-on
# setting at boot while personalDNSfilter's own receiver restarts its VPN, so
# disable the package whenever present. Clear the setting only if it names this
# app; other VPN apps are never changed. Keep this last because stopping a VPN
# can briefly interrupt a network ADB connection.
if [[ $mode != permissions ]]; then
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
fi

# Activate automatic takeover only after installation, permission checks, service
# startup, foreground verification and the legacy VPN cleanup have all succeeded.
sh_ settings put global dumbtv_homesentinel_enabled 1 >/dev/null ||
  { echo "error: DumbTV is installed, but automatic HOME takeover remains suspended" >&2; exit 1; }
takeover=$(sh_ settings get global dumbtv_homesentinel_enabled | tr -d '\r')
[[ $takeover == 1 ]] ||
  { echo "error: automatic HOME takeover could not be enabled and verified" >&2; exit 1; }
TAKEOVER_SUSPENDED=0
trap - EXIT
echo "automatic HOME takeover enabled and verified"
