#!/usr/bin/env bash
# Proof that it worked: HOME must land on dumbtv home unless the user kept Amazon's.
# DNS filtering is external and is verified in the Pi-hole admin/query log.
# Usage: ./scripts/verify.sh
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
parse_flags "$@"
need_device
load_conf

fail=0
printf '\n%-10s %-38s %s\n' check result details
printf '%-10s %-38s %s\n' '----------' '--------------------------------------' '----------------------'

home=$(front_activity)
if [[ $LAUNCHER == 0 ]]; then
  printf '%-10s %-38s %s\\n' skip home "LAUNCHER=0, not checked"
elif [[ $SDK != 25 ]]; then
  printf '%-10s %-38s %s\\n' FAIL home "launcher supports only Fire OS 6 / API 25 (device reports API $SDK)"; fail=1
else
  keeper=$(dev 'dumpsys activity services tv.dumbtv.home' | grep -c 'isForeground=true' || true)
  if [[ ${keeper:-0} -gt 0 ]]; then
    printf '%-10s %-38s %s\\n' ok home "Keeper armed, front is ${home:-unknown}"
  else
    printf '%-10s %-38s %s\\n' FAIL home "Keeper not running; re-run launcher.sh"; fail=1
  fi
  usage_access=$(dev 'appops get tv.dumbtv.home GET_USAGE_STATS' | tr -d '\\r')
  if grep -q 'GET_USAGE_STATS: allow' <<<"$usage_access"; then
    printf '%-10s %-38s %s\\n' ok usage-access "UsageStats permission granted"
  else
    printf '%-10s %-38s %s\\n' FAIL usage-access "${usage_access:-could not read app-op}"; fail=1
  fi
  read_logs=$(dev 'pm check-permission android.permission.READ_LOGS tv.dumbtv.home' | tr -d '\\r')
  if [[ $read_logs == granted ]]; then
    printf '%-10s %-38s %s\\n' ok logcat "READ_LOGS granted"
  else
    printf '%-10s %-38s %s\\n' warn logcat "READ_LOGS unavailable; Keeper will use UsageStats and screen broadcasts"
  fi
fi

off=$(dev 'pm list packages -d' | tr -d '\r')
printf '%-10s %-38s %s\n' packages "" "$(printf '%s\n' "$off" | grep -c '^package:') disabled"

# A Fire OS update can switch a disabled package back on or put an ad-tracking
# setting back to Amazon's default, and nothing on the TV says so.
installed=$(dev 'pm list packages' | tr -d '\r')
back=""
for p in $(cleanup_list); do
  protected_by_policy "$p" && continue
  keep_pkg "$p" && continue
  # Amazon refuses to disable these; lockdown.sh starves them instead.
  list_of "$(fos protected.txt)" | grep -qxF "$p" && continue
  grep -qxF "package:$p" <<<"$installed" || continue
  grep -qxF "package:$p" <<<"$off" && continue
  back="$back $p"
done
if [[ -n $back ]]; then
  printf '%-10s %-38s %s\n' warn packages "enabled again:$back; run debloat.sh"
fi

current=$(dev 'settings list secure; settings list global' | tr -d '\r')
reset=""; checked=0
while read -r ns key value; do
  checked=$((checked+1))
  now=$(printf '%s\n' "$current" | awk -v k="$key" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }')
  [[ $now == "$value" ]] || reset="$reset $key=${now:-unset}"
done < <(list_of "$(fos settings.txt)" | sed 's/ = / /')
if [[ -n $reset ]]; then
  printf '%-10s %-38s %s\n' warn settings "back to Amazon's values:$reset; run debloat.sh"
else
  printf '%-10s %-38s %s\n' ok settings "$checked privacy settings as set"
fi

# lockdown.sh's Data Saver is what keeps the protected packages off metered networks.
case $(dev 'cmd netpolicy get restrict-background' | tr -d '\r') in
  *disabled*) printf '%-10s %-38s %s\n' warn data-saver "off, so protected packages use data freely; run lockdown.sh" ;;
  *enabled*) printf '%-10s %-38s %s\n' ok data-saver "on" ;;
  *) printf '%-10s %-38s %s\n' skip data-saver "could not read it" ;;
esac
dream=$(dream_package)
if [[ -n $dream ]] && grep -qx "package:$dream" <<<"$off"; then
  printf '%-10s %-38s %s\n' warn screensaver "$dream is disabled, so the TV sleeps at the timeout; run debloat.sh"
fi

# The launcher on the TV can be older than the release this repo ships. Releases
# up to 1.2 let the TV sleep while an HDMI picture was up (a passthrough input
# counts as no activity), and nothing else on the TV says so.
if [[ $LAUNCHER == 0 ]]; then
  printf '%-10s %-38s %s\n' skip launcher "LAUNCHER=0, not checked"
else
  have=$(dev 'dumpsys package tv.dumbtv.home' | sed -n 's/.*versionName=\([0-9][0-9.]*\).*/\1/p' | head -1)
  want=$(sed -n 's/^VERSION=//p' "$ROOT/launcher/version.env" 2>/dev/null | head -1)
  if [[ -z $have ]]; then
    printf '%-10s %-38s %s\n' skip launcher "dumbtv home not installed"
  elif [[ -z $want || $have == "$want" ]]; then
    printf '%-10s %-38s %s\n' ok launcher "installed ${have}"
  elif [[ $(printf '%s\n%s\n' "$have" "$want" | sort -t. -k1,1n -k2,2n -k3,3n | head -1) == "$have" ]]; then
    printf '%-10s %-38s %s\n' FAIL launcher "installed ${have}, release is ${want}; run ./scripts/launcher.sh -y"; fail=1
  else
    printf '%-10s %-38s %s\n' ok launcher "installed ${have} (newer than ${want})"
  fi
fi

if [[ $LAUNCHER == 0 ]]; then
  printf '%-10s %-38s %s\n' skip alarm "LAUNCHER=0, not checked"
elif [[ $SDK -lt 28 ]]; then
  printf '%-10s %-38s %s\n' skip alarm "Fire OS 6 Keeper uses moveTaskToFront"
else
  # dumbtv home needs this to answer HOME immediately; an update can drop it.
  if [[ $futurity == 0 ]]; then
    printf '%-10s %-38s %s\n' ok alarm "min_futurity=0"
  else
    printf '%-10s %-38s %s\n' warn alarm "missing min_futurity=0 (HOME is slower, still works)"
  fi
fi

if [[ $fail == 0 ]]; then
  printf '\nall checks passed\n'
else
  printf '\nfailures above. Recovery steps: docs/TROUBLESHOOTING.md\n'
fi
exit $fail
