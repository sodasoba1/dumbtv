#!/usr/bin/env bash
# One-time migration from DumbTV's former optional personalDNSfilter integration.
# Only touches the VPN selection when it explicitly belongs to dnsfilter.android.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
parse_flags "$@"
need_device

legacy_vpn=$(dev1 settings get secure always_on_vpn_app | tr -d '\r')
if [[ $legacy_vpn != dnsfilter.android ]]; then
  note "no legacy personalDNSfilter always-on VPN selection"
  exit 0
fi

step "retire legacy personalDNSfilter"
note "clearing only the old personalDNSfilter VPN selection; other VPN apps are untouched"
acmd settings delete secure always_on_vpn_app
if present dnsfilter.android; then
  acmd pm disable-user --user 0 dnsfilter.android
  note "personalDNSfilter disabled; remove it from Settings > Applications if desired"
fi
[[ $DRY_RUN == 1 ]] || { sleep 2; adb_reconnect; }
