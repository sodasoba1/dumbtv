#!/usr/bin/env bash
# One-time migration from DumbTV's former optional personalDNSfilter integration.
# Disable only that app if present; clear the always-on VPN selection only if it
# explicitly belongs to that app. Other VPN apps and settings are untouched.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
parse_flags "$@"
need_device

legacy_vpn=$(dev1 settings get secure always_on_vpn_app | tr -d '\r')

# Stop the service before clearing Android's always-on selection. Stopping a VPN
# can drop network ADB, so reconnect before issuing the next device command.
if present dnsfilter.android; then
  step "remove legacy personalDNSfilter privileges"
  acmd appops set dnsfilter.android ACTIVATE_VPN default >/dev/null 2>&1 || true
  acmd appops set dnsfilter.android AUTO_START default >/dev/null 2>&1 || true
  acmd dumpsys deviceidle whitelist -dnsfilter.android >/dev/null 2>&1 || true
  acmd pm revoke dnsfilter.android android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
  acmd pm revoke dnsfilter.android android.permission.WRITE_EXTERNAL_STORAGE >/dev/null 2>&1 || true
  step "disable legacy personalDNSfilter app"
  disable_rc=0
  acmd pm disable-user --user 0 dnsfilter.android || disable_rc=$?
  if [[ $DRY_RUN == 0 ]]; then
    sleep 2
    adb_reconnect
    if ! dev 'pm list packages -d' | tr -d '\r' | grep -qx 'package:dnsfilter.android'; then
      die "personalDNSfilter is still enabled after the disable request (exit $disable_rc)"
    fi
  fi
  if [[ $DRY_RUN == 1 ]]; then
    note "[dry-run] would disable personalDNSfilter"
  else
    note "personalDNSfilter disabled; uninstall it from Settings > Applications if desired"
  fi
else
  note "personalDNSfilter is not installed"
fi

if [[ $legacy_vpn == dnsfilter.android ]]; then
  step "clear legacy personalDNSfilter VPN selection"
  acmd settings delete secure always_on_vpn_app || die "could not clear the legacy personalDNSfilter VPN selection"
  if [[ $DRY_RUN == 0 && $(dev1 settings get secure always_on_vpn_app | tr -d '\r') == dnsfilter.android ]]; then
    die "the legacy personalDNSfilter VPN selection is still set"
  fi
fi
