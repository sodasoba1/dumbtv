#!/usr/bin/env bash
# Undoes the package, app-op, network-policy and launcher changes.
# Apps that were uninstalled from /data have to be reinstalled from the store.
# Usage: ./scripts/restore.sh [--dry-run] [-y]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
parse_flags "$@"
need_device
need_supported_fireos
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
if [[ $DRY_RUN == 0 ]]; then
  ensure_report_device
fi

# A missing key in a real snapshot means it was absent on this device; preserve
# that absence. The sentinel is internal and never written to Android settings.
ABSENT_SETTING="__DUMBTW_SETTING_WAS_ABSENT__"

# Prefer the value this device actually had before the pass (snapshotted by
# preflight.sh); use bundled defaults only for installations without a snapshot.
restore_value() {
  local ns=$1 key=$2 fallback=$3 snapshot="$REPORT/settings-before-$1.txt" v
  if [[ -f $snapshot ]]; then
    # Preserve empty values and distinguish them from keys absent in the baseline.
    if grep -q "^$key=" "$snapshot"; then
      v=$(tr -d '\r' < "$snapshot" | grep -m1 "^$key=" | cut -d= -f2-)
      printf '%s' "$v"
    else
      printf '%s' "$ABSENT_SETTING"
    fi
    return 0
  fi
  printf '%s' "$fallback"
}

restore_setting() {
  local ns=$1 key=$2 value=$3 actual expected
  if [[ $value == "$ABSENT_SETTING" ]]; then
    acmd settings delete "$ns" "$key" >/dev/null ||
      die "could not delete original absent setting $ns/$key"
    expected=null
  else
    acmd settings put "$ns" "$key" "$value" >/dev/null ||
      die "could not restore setting $ns/$key"
    expected=$value
  fi
  if [[ $DRY_RUN == 0 ]]; then
    actual=$(dev1 settings get "$ns" "$key" | tr -d '\r')
    [[ $actual == "$expected" ]] ||
      die "setting $ns/$key did not restore (expected '$expected', got '$actual'); baseline retained"
  fi
}

package_snapshot="$REPORT/packages-before.txt"
was_enabled_before() {
  local state
  [[ ! -f $package_snapshot ]] && return 0
  state=$(awk -F '\t' -v package="$1" '$1 == package {print $2; exit}' "$package_snapshot")
  [[ $state == enabled ]]
}

step "packages"
for p in $(restore_list); do
  # When a snapshot exists, only re-enable packages that were enabled before DumbTV
  # ran. Older installations without a snapshot retain the historical restore path.
  if ! was_enabled_before "$p"; then
    printf '  %-46s left disabled (was not enabled in the original snapshot)\n' "$p"
    continue
  fi
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
installed=$(dev 'pm list packages' | tr -d '\r')
for p in $(list_of "$(fos never-disable.txt)" | awk '{print $1}'); do
  grep -qxF "package:$p" <<<"$installed" || continue
  # Query the actual per-user state; pm list packages -d can omit disabled apps.
  state=$(package_user_enabled_state "$p")
  case $state in
    2)
      printf '  %-46s left disabled by Fire OS (state 2)\n' "$p"
      continue ;;
    3) ;;
    *) continue ;;
  esac
  was_enabled_before "$p" || {
    printf '  %-46s left disabled (was not enabled in the original snapshot)\n' "$p"
    continue
  }
  b=$(acmd pm enable --user 0 "$p" 2>&1 | head -1)
  printf '  %-46s %s\n' "$p" "${b:-no output}"
done

step "screensaver"
if [[ $(dev1 settings get secure screensaver_components) == "$AOSP_DREAM" ]]; then
  want=$(restore_value secure screensaver_components "$(dev1 settings get secure screensaver_default_component)")
  if [[ -n $want && $want != null && $want != "$AOSP_DREAM" ]]; then
    restore_setting secure screensaver_components "$want"
    printf '  %-46s %s\n' screensaver_components "$want"
  fi
else
  note "unchanged"
fi

step "restore background execution and network policy"
if [[ -f $REPORT/lockdown-before.tsv ]]; then
  while IFS=

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
  restore_setting "$ns" "$key" "$want"
  printf '  %-46s %s\n' "$key" "${want/$ABSENT_SETTING/<absent>} "
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
  step "remove legacy personalDNSfilter privileges"
  acmd appops set dnsfilter.android ACTIVATE_VPN default >/dev/null || true
  acmd appops set dnsfilter.android AUTO_START default >/dev/null || true
  acmd dumpsys deviceidle whitelist -dnsfilter.android >/dev/null || true
  acmd pm revoke dnsfilter.android android.permission.READ_EXTERNAL_STORAGE >/dev/null || true
  acmd pm revoke dnsfilter.android android.permission.WRITE_EXTERNAL_STORAGE >/dev/null || true
  if [[ $DRY_RUN == 1 ]]; then
    note "[dry-run] would disable personalDNSfilter"
  else
    note "personalDNSfilter disabled; remove it from Settings > Applications if desired"
  fi
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

# Re-check settings after the reboot: a successful reboot is not proof that
# Android persisted the requested values. Keep every snapshot if any check fails.
while read -r ns key value; do
  [[ -z ${ns:-} || $ns == \#* ]] && continue
  want=$(restore_value "$ns" "$key" "$value")
  if [[ $want == "$ABSENT_SETTING" ]]; then expected=null; else expected=$want; fi
  actual=$(dev1 settings get "$ns" "$key" | tr -d '\\r')
  [[ $actual == "$expected" ]] ||
    die "post-reboot setting verification failed for $ns/$key (expected '$expected', got '$actual'); snapshots retained"
done < <(list_of "$(fos settings.restore.txt)" | sed 's/ = / /')
actual_data_saver=$(dev 'cmd netpolicy get restrict-background' | tr -d '\r')
case $data_saver:$actual_data_saver in
  enabled:*enabled*|disabled:*disabled*) ;;
  *) die "post-reboot Data Saver verification failed (expected $data_saver, got $actual_data_saver); snapshots retained" ;;
esac
for ns in secure global; do
  snapshot="$REPORT/settings-before-$ns.txt"
  [[ -f $snapshot ]] || continue
  while IFS= read -r line; do
    [[ $line == *=* ]] || continue
    key=${line%%=*}; expected=${line#*=}
    actual=$(dev1 settings get "$ns" "$key" | tr -d '\\r')
    [[ $actual == "$expected" ]] ||
      die "post-reboot baseline verification failed for $ns/$key; snapshots retained"
  done < "$snapshot"
done

# A completed restore ends this cleanup cycle. Remove only the original-state
# snapshots so a later run records the device's new baseline instead of reusing
# stale values from this one.
[[ ! -f $REPORT/debloat.txt ]] || mv "$REPORT/debloat.txt" "$REPORT/debloat-last.txt"
rm -f "$REPORT/packages-before.txt" "$REPORT/settings-before-secure.txt" \
  "$REPORT/settings-before-global.txt" "$REPORT/lockdown-before.tsv" \
  "$REPORT/data-saver-before.txt"
note "restored. dumbtv home and SmartTube (if added) remain installed; remove them in"
note "Settings > Applications if you want them gone too."
\t' read -r p uid bg any was_blacklisted; do
    [[ -z ${p:-} ]] && continue
    acmd appops set "$p" RUN_IN_BACKGROUND "$bg" >/dev/null ||
      die "could not restore RUN_IN_BACKGROUND for $p"
    if [[ $SDK -ge 28 && $any != not-applicable ]]; then
      acmd appops set "$p" RUN_ANY_IN_BACKGROUND "$any" >/dev/null ||
        die "could not restore RUN_ANY_IN_BACKGROUND for $p"
    fi
    if [[ $was_blacklisted == 0 ]]; then
      acmd cmd netpolicy remove restrict-background-blacklist "$uid" >/dev/null ||
        die "could not remove DumbTV's network restriction for $p"
    fi
    printf '  %-46s AppOps restored; original blacklist=%s\n' "$p" "$was_blacklisted"
  done < "$REPORT/lockdown-before.tsv"
else
  # v1.17 did not retain the original modes. Limit the compatibility fallback
  # to the protected package list; never replay the broad debloat report.
  note "legacy installation has no AppOps baseline; restoring protected packages to allow"
  for p in $(list_of "$(fos protected.txt)"); do
    present "$p" || continue
    bg_on "$p"
    uid=$(uid_of "$p")
    [[ -n $uid ]] && acmd cmd netpolicy remove restrict-background-blacklist "$uid" >/dev/null
  done
fi

data_saver=disabled
if [[ -f $REPORT/data-saver-before.txt ]]; then
  data_saver=$(tr -d '\r\n' < "$REPORT/data-saver-before.txt")
fi
case $data_saver in enabled|disabled) ;; *) die "invalid saved Data Saver state: $data_saver" ;; esac
acmd cmd netpolicy set restrict-background "$data_saver" >/dev/null ||
  die "could not restore original Data Saver state"
if [[ $DRY_RUN == 0 ]]; then
  actual_data_saver=$(dev 'cmd netpolicy get restrict-background' | tr -d '\r')
  case $data_saver:$actual_data_saver in
    enabled:*enabled*|disabled:*disabled*) ;;
    *) die "Data Saver restore did not verify (expected $data_saver, got $actual_data_saver)" ;;
  esac
fi

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
  restore_setting "$ns" "$key" "$want"
  printf '  %-46s %s\n' "$key" "${want/$ABSENT_SETTING/<absent>} "
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
  step "remove legacy personalDNSfilter privileges"
  acmd appops set dnsfilter.android ACTIVATE_VPN default >/dev/null || true
  acmd appops set dnsfilter.android AUTO_START default >/dev/null || true
  acmd dumpsys deviceidle whitelist -dnsfilter.android >/dev/null || true
  acmd pm revoke dnsfilter.android android.permission.READ_EXTERNAL_STORAGE >/dev/null || true
  acmd pm revoke dnsfilter.android android.permission.WRITE_EXTERNAL_STORAGE >/dev/null || true
  if [[ $DRY_RUN == 1 ]]; then
    note "[dry-run] would disable personalDNSfilter"
  else
    note "personalDNSfilter disabled; remove it from Settings > Applications if desired"
  fi
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

# Re-check settings after the reboot: a successful reboot is not proof that
# Android persisted the requested values. Keep every snapshot if any check fails.
while read -r ns key value; do
  [[ -z ${ns:-} || $ns == \#* ]] && continue
  want=$(restore_value "$ns" "$key" "$value")
  if [[ $want == "$ABSENT_SETTING" ]]; then expected=null; else expected=$want; fi
  actual=$(dev1 settings get "$ns" "$key" | tr -d '\\r')
  [[ $actual == "$expected" ]] ||
    die "post-reboot setting verification failed for $ns/$key (expected '$expected', got '$actual'); snapshots retained"
done < <(list_of "$(fos settings.restore.txt)" | sed 's/ = / /')
for ns in secure global; do
  snapshot="$REPORT/settings-before-$ns.txt"
  [[ -f $snapshot ]] || continue
  while IFS= read -r line; do
    [[ $line == *=* ]] || continue
    key=${line%%=*}; expected=${line#*=}
    actual=$(dev1 settings get "$ns" "$key" | tr -d '\\r')
    [[ $actual == "$expected" ]] ||
      die "post-reboot baseline verification failed for $ns/$key; snapshots retained"
  done < "$snapshot"
done

# A completed restore ends this cleanup cycle. Remove only the original-state
# snapshots so a later run records the device's new baseline instead of reusing
# stale values from this one.
[[ ! -f $REPORT/debloat.txt ]] || mv "$REPORT/debloat.txt" "$REPORT/debloat-last.txt"
rm -f "$REPORT/packages-before.txt" "$REPORT/settings-before-secure.txt" \
  "$REPORT/settings-before-global.txt"
note "restored. dumbtv home and SmartTube (if added) remain installed; remove them in"
note "Settings > Applications if you want them gone too."
