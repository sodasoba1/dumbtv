#!/usr/bin/env bash
# Verify that this Fire TV uses the expected Pi-hole and that representative
# DumbTV deny/allow rules are effective. Runs DNS lookups on the TV itself.
# Usage: bash ./scripts/dns-check.sh <pihole-ip>
set -uo pipefail
source "$(dirname "\${BASH_SOURCE[0]}")/lib.sh"

[[ $# == 1 && -n $1 ]] || die "usage: bash ./scripts/dns-check.sh <pihole-ip>"
PIHOLE_IP=$1
case $PIHOLE_IP in *[!0-9a-fA-F:.]*|'') die "Pi-hole address must be an IPv4 or IPv6 address" ;; esac

need_device
fail=0
printf '\nDumbTV DNS verification on %s\n' "$SERIAL"
printf '%-9s %-42s %s\n' CHECK RESULT DETAILS
printf '%-9s %-42s %s\n' '---------' '------------------------------------------' '-------------------------'

dns1=$(dev1 getprop net.dns1)
dns2=$(dev1 getprop net.dns2)
if [[ $dns1 == "$PIHOLE_IP" || $dns2 == "$PIHOLE_IP" ]]; then
  printf '%-9s %-42s %s\n' resolver PASS "Fire TV lists Pi-hole ($PIHOLE_IP) as DNS"
else
  printf '%-9s %-42s %s\n' resolver WARN "net.dns1=\${dns1:-unset}; net.dns2=\${dns2:-unset}; expected $PIHOLE_IP"
  printf '          %-42s %s\n' '' "A router that forwards DNS to Pi-hole may show a different resolver; confirm in Query Log."
fi

# This real hostname is explicitly present in DumbTV's supplemental blocklist.
# Check the Query Log after the test: a cached answer will not create a new entry.
blocked_host=aax.amazon-adsystem.com
blocked_output=$(dev "ping -c 1 -W 2 $blocked_host" 2>&1 || true)
blocked_ip=$(printf '%s\n' "$blocked_output" | sed -nE 's/.*\(([0-9a-fA-F:.]+)\).*/\1/p' | head -1)
if printf '%s\n' "$blocked_output" | grep -qiE 'unknown host|bad address|cannot resolve|name or service not known' ||
   [[ $blocked_ip == 0.0.0.0 || $blocked_ip == 127.0.0.1 || $blocked_ip == :: ]]; then
  printf '%-9s %-42s %s\n' block PASS "DumbTV test hostname was blocked"
elif [[ -n $blocked_ip ]]; then
  printf '%-9s %-42s %s\n' block FAIL "resolved to $blocked_ip; expected Pi-hole to block it"
  fail=1
else
  printf '%-9s %-42s %s\n' block UNKNOWN "could not interpret ping output; inspect device DNS support"
  fail=1
fi

# This exact AIV delivery hostname is in DumbTV's Prime Video compatibility
# allowlist. The lookup must resolve; ICMP reachability is deliberately ignored.
allowed_host=api.eu-west-1.aiv-delivery.net
allowed_output=$(dev "ping -c 1 -W 2 $allowed_host" 2>&1 || true)
allowed_ip=$(printf '%s\n' "$allowed_output" | sed -nE 's/.*\(([0-9a-fA-F:.]+)\).*/\1/p' | head -1)
if [[ -n $allowed_ip && $allowed_ip != 0.0.0.0 && $allowed_ip != 127.0.0.1 && $allowed_ip != :: ]]; then
  printf '%-9s %-42s %s\n' allow PASS "Prime Video compatibility hostname resolves ($allowed_ip)"
elif printf '%s\n' "$allowed_output" | grep -qiE 'unknown host|bad address|cannot resolve|name or service not known' ||
     [[ $allowed_ip == 0.0.0.0 || $allowed_ip == 127.0.0.1 || $allowed_ip == :: ]]; then
  printf '%-9s %-42s %s\n' allow FAIL "compatibility hostname is blocked; check Pi-hole allowlist"
  fail=1
else
  printf '%-9s %-42s %s\n' allow UNKNOWN "could not interpret ping output; inspect device DNS support"
  fail=1
fi

printf "\nPi-hole Query Log: filter by this Fire TV's IP and confirm both test names appear.\n"
printf 'The block test should be blocked; the allow test should be allowed.\n'
printf 'DNS results do not prove that every entry in every subscribed list is working.\n'
exit "$fail"
