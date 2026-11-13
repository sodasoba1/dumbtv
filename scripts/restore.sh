#!/usr/bin/env bash
# Undoes the package, app-op, network-policy and launcher changes.
# Apps that were uninstalled from /data have to be reinstalled from the store.
# Usage: ./scripts/restore.sh [--dry-run] [-y]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
parse_flags "$@"
need_device
load_conf

# Everything debloat.sh or youtube.sh may have switched off, under any answers.
restore_list() {
  {
    list_of "$(fos packages.txt)"
    list_of "$(fos alexa.txt)"
    list_of "$(fos user-apps.txt)"
    printf '%s\n' $REMOVE_EXTRA com.amazon.firetv.youtube
  } | awk 'NF {print $1}' | awk '!seen[$0]++'
}

step "plan"
note "turn back on every package dumbtv can disable: the ads list, Alexa, apps you named, YouTube"
note "clear app-ops and network blacklists"
note "clear any legacy always-on VPN setting and detach the launcher hook"
note "put back every setting from report/settings-before-*.txt, or the bundled defaults"
note "the TV reboots when this finishes"
if [[ $DRY_RUN == 0 ]]; then
  confirm "Restore Amazon defaults on $SERIAL?" || die "cancelled"
fi

mkdir -p "$REPORT"

# Prefer the value this device actually had before the pass (snapshotted by
# preflight.sh); fall back to the stock defaults in data/.
restore_value() {
  local ns=$1 key=$2 fallback=$3 snapshot="$REPORT/settings-before-$1.txt" v
  if [[ -f $snapshot ]]; then
    # The snapshot is raw adb output: a CR left on the value would be written back with it.
    v=$(tr -d '\r' < "$snapshot" | grep -m1 "^$key=" | cut -d= -f2-)
    [[ -n $v ]] && { printf '%s' "$v"; return; }
  fi
  printf '%s' "$fallback"
}

step "packages"
for p in $(restore_list); do
  # Older versions uninstalled some system apps for user 0; install-existing brings
  # those back where the build has it. Store apps come back from the store.
  if ! present "$p"; then
    [[ $REINSTALL == 1 ]] || continue
    a=$(acmd cmd package install-existing "$p" 2>&1 | head -1)
    present "$p" || continue
  else
    a="installed"
  fi
  b=$(acmd pm enable --user 0 "$p" 2>&1 | head -1)
  bg_on "$p"
  uid=$(uid_of "$p")
  [[ -n $uid ]] && acmd cmd netpolicy remove restrict-background-blacklist "$uid" >/dev/null 2>&1
  printf '  %-46s %s / %s\n' "$p" "${a:-no output}" "${b:-no output}"
done

# Older lists disabled packages that have since moved to never-disable.txt
# (com.amazon.firebat is Prime Video), so the loop above no longer reaches them.
step "never-disable packages left disabled"
off=$(dev 'pm list packages -d' | sed 's/^package://' | tr -d '\r')
for p in $(list_of "$(fos never-disable.txt)" | awk '{print $1}'); do
  grep -qx "$p" <<<"$off" || continue
  b=$(acmd pm enable --user 0 "$p" 2>&1 | head -1)
  printf '  %-46s %s\n' "$p" "${b:-no output}"
done

step "screensaver"
if [[ $(dev1 settings get secure screensaver_components) == "$AOSP_DREAM" ]]; then
  want=$(restore_value secure screensaver_components "$(dev1 settings get secure screensaver_default_component)")
  if [[ -n $want && $want != null && $want != "$AOSP_DREAM" ]]; then
    acmd settings put secure screensaver_components "$want" >/dev/null
    printf '  %-46s %s\n' screensaver_components "$want"
  fi
else
  note "unchanged"
fi

step "packages that were only starved"
if [[ -f $REPORT/debloat.txt ]]; then
  while IFS=$'\t' read -r p state rest; do
    [[ -z ${p:-} ]] && continue
    bg_on "$p"
    uid=$(uid_of "$p")
    [[ -n $uid ]] && acmd cmd netpolicy remove restrict-background-blacklist "$uid" >/dev/null 2>&1
    printf '  %-46s %s\n' "$p" "${state:-unknown}"
  done < "$REPORT/debloat.txt"
else
  note "no report/debloat.txt, relying on protected.txt only"
  for p in $(list_of "$(fos protected.txt)"); do
    bg_on "$p"
    uid=$(uid_of "$p")
    [[ -n $uid ]] && acmd cmd netpolicy remove restrict-background-blacklist "$uid" >/dev/null 2>&1
    printf '  %-46s cleared\n' "$p"
  done
fi
acmd cmd netpolicy set restrict-background false >/dev/null 2>&1

step "on-device hooks"
if [[ $DRY_RUN == 1 ]]; then
  note "[dry-run] ADB=$ADB ADB_SERIAL=$SERIAL launcher/install.sh --undo"
else
  ADB=$ADB ADB_SERIAL=$SERIAL "$ROOT/launcher/install.sh" --undo >/dev/null ||
    die "could not hand HOME back to Amazon's launcher; run ./launcher/install.sh --undo"
fi
note "HOME handed back to Amazon's launcher"

step "settings"
while read -r ns key value; do
  [[ -z ${ns:-} || $ns == \#* ]] && continue
  want=$(restore_value "$ns" "$key" "$value")
  acmd settings put "$ns" "$key" "$want" >/dev/null
  printf '  %-46s %s\n' "$key" "$want"
done < <(list_of "$(fos settings.restore.txt)" | sed 's/ = / /')

# Retire only the old personalDNSfilter integration. Disable the app if present,
# then clear its always-on selection only if it names that app. Stopping a VPN
# can drop network ADB, so reconnect before issuing further device commands.
legacy_vpn=$(dev1 settings get secure always_on_vpn_app | tr -d '\r')
if present dnsfilter.android; then
  step "disable legacy personalDNSfilter app"
  disable_rc=0
  acmd pm disable-user --user 0 dnsfilter.android || disable_rc=$?
  if [[ $DRY_RUN == 0 ]]; then
    sleep 3
    adb_reconnect
    if ! dev 'pm list packages -d' | tr -d '\r' | grep -qx 'package:dnsfilter.android'; then
      die "personalDNSfilter is still enabled after the disable request (exit $disable_rc)"
    fi
  fi
  note "personalDNSfilter disabled; remove it from Settings > Applications if desired"
fi
if [[ $legacy_vpn == dnsfilter.android ]]; then
  step "clear legacy personalDNSfilter VPN selection"
  acmd settings delete secure always_on_vpn_app || die "could not clear the legacy personalDNSfilter VPN selection"
  if [[ $DRY_RUN == 0 && $(dev1 settings get secure always_on_vpn_app | tr -d '\r') == dnsfilter.android ]]; then
    die "the legacy personalDNSfilter VPN selection is still set"
  fi
fi

[[ $DRY_RUN == 1 ]] && { note "[dry-run] done"; exit 0; }
reboot_wait
note "restored. dumbtv home and SmartTube (if added) remain installed; remove them in"
note "Settings > Applications if you want them gone too."
