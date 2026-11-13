#!/usr/bin/env bash
# Read-only DNS diagnostics. Runs before DumbTV changes anything on the TV.
# Usage: bash ./scripts/dns-preflight.sh [--pihole-ip IP] [--unbound-ip IP]
#        [--block-alexa] [--block-prime-video]
set -uo pipefail
DIR=$(cd "$(dirname "\${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$DIR/.." && pwd)
source "$DIR/lib.sh"

PIHOLE_IP=""
UNBOUND_IP=""
BLOCK_ALEXA=0
BLOCK_PRIME=0
while [[ $# -gt 0 ]]; do
  case $1 in
    --pihole-ip|--unbound-ip)
      [[ $# -ge 2 ]] || die "$1 requires an IP address"
      case $2 in *[!0-9a-fA-F:.]*|'') die "$1 requires an IP address" ;; esac
      if [[ $1 == --pihole-ip ]]; then PIHOLE_IP=$2; else UNBOUND_IP=$2; fi
      shift 2
      ;;
    --block-alexa) BLOCK_ALEXA=1; shift ;;
    --block-prime-video) BLOCK_PRIME=1; shift ;;
    --help|-h)
      printf 'Usage: bash ./scripts/dns-preflight.sh [--pihole-ip IP] [--unbound-ip IP] [--block-alexa] [--block-prime-video]\n'
      exit 0
      ;;
    *) die "unknown option: $1 (use --help)" ;;
  esac
done

need_device
META="$DATA/blocklists/domain-purpose.tsv"
[[ -f $META ]] || die "missing domain metadata: $META"

step "DNS pre-installation check (read-only)"
note "device        $SERIAL"
note "model         $(dev1 getprop ro.product.model)"
note "Fire OS       $(fireos_name)"
note "No settings, apps, packages, VPNs or Pi-hole configuration will be changed."

step "configured DNS resolvers"
declare -a resolvers
for prop in net.dns1 net.dns2 dhcp.wlan0.dns1 dhcp.wlan0.dns2; do
  value=$(dev1 getprop "$prop")
  if [[ -n $value && $value != "0.0.0.0" ]]; then
    found=0
    for old in "\${resolvers[@]+"$\{resolvers[@]}"}"; do [[ $old == "$value" ]] && found=1; done
    [[ $found == 1 ]] || resolvers+=("$value")
  fi
done
if [[ \${#resolvers[@]} -eq 0 ]]; then
  note "DNS IPs        unavailable from Android properties"
else
  for ip in "\${resolvers[@]}"; do
    role="IP only; resolver type not identifiable from address alone"
    if [[ -n $PIHOLE_IP && $ip == "$PIHOLE_IP" ]]; then role="Pi-hole (matched supplied Pi-hole IP)"
    elif [[ -n $UNBOUND_IP && $ip == "$UNBOUND_IP" ]]; then role="Unbound (matched supplied Unbound IP)"
    elif [[ $ip == "$(gateway)" ]]; then role="router/gateway; upstream resolver cannot be inferred"
    fi
    note "DNS server     $ip — $role"
  done
fi
[[ -n $PIHOLE_IP ]] && note "Pi-hole label  $PIHOLE_IP (provided by operator)"
[[ -n $UNBOUND_IP ]] && note "Unbound label  $UNBOUND_IP (provided by operator)"
note "resolver note  Pi-hole forwarding to Unbound normally means the TV sees Pi-hole, not Unbound."
note "verification   DNS replies alone cannot prove which server answered; cross-check Pi-hole Query Log."

step "domain policy checks"
note "BLOCKED means the device received an NXDOMAIN/sinkhole-style response."
note "RESOLVES means DNS returned an address. UNKNOWN means the lookup could not be classified."
note "A blocked-looking response can also be natural NXDOMAIN or another upstream filter; use Pi-hole Query Log for attribution."
printf '%-8s %-43s %-27s %s\n' EXPECTED DOMAIN SERVICE RESULT

total=0
matched=0
mismatch=0
unknown=0
while IFS=$'\t' read -r domain profile service purpose confidence evidence; do
  [[ -z $domain || $domain == \#* ]] && continue
  expected=""
  case $profile in
    base) expected=BLOCKED ;;
    alexa) if [[ $BLOCK_ALEXA == 1 ]]; then expected=BLOCKED; else expected=RESOLVES; fi ;;
    prime) if [[ $BLOCK_PRIME == 1 ]]; then expected=BLOCKED; else expected=RESOLVES; fi ;;
    allow) expected=RESOLVES ;;
    prime-allow) [[ $BLOCK_PRIME == 1 ]] && continue; expected=RESOLVES ;;
    *) continue ;;
  esac

  # Query the exact source hostname. This is compatible with both ABP exports
  # and legacy exact-domain lists. Do not infer subdomain wildcard behaviour.
  output=$(dev "ping -c 1 -W 1 $domain" 2>&1 || true)
  ip=$(printf '%s\n' "$output" | sed -nE 's/.*\(([0-9a-fA-F:.]+)\).*/\1/p' | head -1)
  if printf '%s\n' "$output" | grep -qiE 'unknown host|bad address|cannot resolve|name or service not known|no address associated|NXDOMAIN' ||
     [[ $ip == 0.0.0.0 || $ip == 127.0.0.1 || $ip == :: || $ip == ::1 ]]; then
    actual=BLOCKED
  elif [[ -n $ip ]]; then
    actual=RESOLVES
  else
    actual=UNKNOWN
  fi

  total=$((total + 1))
  if [[ $actual == UNKNOWN ]]; then
    unknown=$((unknown + 1))
    result="UNKNOWN"
  elif [[ $actual == "$expected" ]]; then
    matched=$((matched + 1))
    result="OK ($actual)"
  else
    mismatch=$((mismatch + 1))
    result="MISMATCH ($actual)"
  fi
  printf '%-8s %-43s %-27s %s\n' "$expected" "$domain" "$service" "$result"
done < "$META"

step "summary"
note "checks        $total"
note "as expected   $matched"
note "mismatches    $mismatch"
note "unknown       $unknown"
note "profile       Alexa=$([[ $BLOCK_ALEXA == 1 ]] && echo blocked || echo allowed); Prime Video=$([[ $BLOCK_PRIME == 1 ]] && echo blocked || echo allowed)"
note "list sources  data/blocklists/extra-hosts.txt, alexa-hosts.txt, prime-video-hosts.txt"
note "Pi-hole files data/blocklists/pihole/v6/, v5/, v5-legacy/"
note "setup guide   docs/PI-HOLE.md"
note "metadata      data/blocklists/domain-purpose.tsv (purpose, confidence and evidence per domain)"
note "limits        checks exact hostnames only; it does not prove every entry in HaGeZi's upstream subscription is active."
note "next step     inspect Pi-hole Query Log to confirm the tested domains were blocked/allowed by the intended client group."

if [[ $mismatch -gt 0 ]]; then exit 1; fi
if [[ $unknown -gt 0 ]]; then exit 2; fi
exit 0
