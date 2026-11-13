#!/usr/bin/env bash
# One-time migration from DumbTV's former optional personalDNSfilter integration.
# Disable only that app if present; clear the always-on VPN selection only if it
# explicitly belongs to that app. Other VPN apps and settings are untouched.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
parse_flags "$@"
need_device

changed=0
legacy_vpn=$(dev1 settings get secure always_on_vpn_app | tr -d '\r')
if [[ $legacy_vpn == dnsfilter.android ]]; then
  step "clear legacy personalDNSfilter VPN selection"
  acmd settings delete secure always_on_vpn_app
  changed=1
fi

if present dnsfilter.android; then
  step "disable legacy personalDNSfilter app"
  acmd pm disable-user --user 0 dnsfilter.android
  note "personalDNSfilter disabled; uninstall it from Settings > Applications if desired"
  changed=1
else
  note "personalDNSfilter is not installed"
fi

if [[ $changed == 1 && $DRY_RUN == 0 ]]; then
  sleep 2
  adb_reconnect
fi
