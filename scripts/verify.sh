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
  printf '%-10s %-38s %s\n' skip home "LAUNCHER=0, not checked"
elif [[ $SDK != 25 ]]; then
  printf '%-10s %-38s %s\n' FAIL home "launcher supports only Fire OS 6 / API 25 (device reports API $SDK)"; fail=1
else
  homesentinel=$(dev 'dumpsys activity services tv.dumbtv.home' | grep -c 'isForeground=true' || true)
  home_package=${home%%/*}
  if [[ ${homesentinel:-0} -gt 0 && $home_package != com.amazon.tv.launcher && -n $home_package ]]; then
    printf '%-10s %-38s %s\n' ok home "HomeSentinel armed; foreground is ${home:-unknown}"
  elif [[ ${homesentinel:-0} -gt 0 ]]; then
    printf '%-10s %-38s %s\n' FAIL home "HomeSentinel is running but Amazon Home is foreground (${home:-unknown})"; fail=1
  else
    printf '%-10s %-38s %s\n' FAIL home "HomeSentinel not running; re-run launcher.sh"; fail=1
  fi
  usage_access=$(dev 'appops get tv.dumbtv.home GET_USAGE_STATS' | tr -d '\r')
  if grep -Eq 'GET_USAGE_STATS:[[:space:]]*allow([,[:space:]]|$)' <<<"$usage_access"; then
    printf '%-10s %-38s %s\n' ok usage-access "UsageStats AppOp allowed"
  else
    printf '%-10s %-38s %s\n' FAIL usage-access "${usage_access:-could not read app-op}"; fail=1
  fi
  auto_start=$(dev 'appops get tv.dumbtv.home AUTO_START' 2>&1 | tr -d '\r')
  if grep -Fq 'Unknown operation string: AUTO_START' <<<"$auto_start"; then
    printf '%-10s %-38s %s\n' skip standby-start "AUTO_START unsupported by this firmware; boot receiver remains available"
  elif grep -Eq 'AUTO_START:[[:space:]]*allow([,[:space:]]|$)' <<<"$auto_start"; then
    printf '%-10s %-38s %s\n' ok standby-start "HomeSentinel AUTO_START allowed"
  else
    printf '%-10s %-38s %s\n' FAIL standby-start "${auto_start:-AUTO_START unavailable}; rerun ./launcher/install.sh"; fail=1
  fi
  package_dump=$(dev 'dumpsys package tv.dumbtv.home' | tr -d '\r')
  if grep -Eq '^[[:space:]]*android\.permission\.READ_LOGS:[[:space:]]*granted=true([,[:space:]]|$)' <<<"$package_dump"; then
    printf '%-10s %-38s %s\n' ok logcat "READ_LOGS granted; ActivityManager monitoring available"
  else
    printf '%-10s %-38s %s\n' FAIL logcat "READ_LOGS not granted; rerun ./launcher/install.sh"; fail=1
  fi
fi

off=$(dev 'pm list packages -d' | tr -d '\r')
printf '%-10s %-38s %s\n' packages "" "$(printf '%s\n' "$off" | grep -c '^package:') disabled"

# A Fire OS update can switch a disabled package back on or put an ad-tracking
# setting back to Amazon's default, and nothing on the TV says so.
installed=$(dev 'pm list packages' | tr -d '\r')

# Never-disable packages must not remain user-disabled. State 2 is an intentional
# system disable and is reported without trying to change it.
protected_bad=""; protected_system=""
while read -r p; do
  [[ -z ${p:-} ]] && continue
  grep -qxF "package:$p" <<<"$installed" || continue
  # Do not trust pm list packages -d to be exhaustive on Fire OS. Query the
  # per-user package state for every installed protected package.
  state=$(package_user_enabled_state "$p")
  case $state in 2) protected_system="$protected_system $p" ;; 3) protected_bad="$protected_bad $p" ;; esac
done < <(list_of "$(fos never-disable.txt)" | awk '{print $1}')
if [[ -n $protected_bad ]]; then
  printf '%-10s %-38s %s\n' FAIL protected "user-disabled:$protected_bad; inspect before re-enabling"; fail=1
elif [[ -n $protected_system ]]; then
  printf '%-10s %-38s %s\n' ok protected "Fire OS-disabled (state 2), left untouched:$protected_system"
else
  printf '%-10s %-38s %s\n' ok protected "never-disable packages are enabled"
fi

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
if [[ $ALEXA == 0 ]]; then
  while read -r ns key value; do
    [[ -z ${ns:-} || $ns == \#* ]] && continue
    checked=$((checked+1))
    now=$(printf '%s\n' "$current" | awk -v k="$key" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }')
    [[ $now == "$value" ]] || reset="$reset $key=${now:-unset}"
  done < <(list_of "$(fos settings.alexa.txt)" | sed 's/ = / /')
fi
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

if [[ $fail == 0 ]]; then
  printf '\nall checks passed\n'
else
  printf '\nfailures above. Recovery steps: docs/TROUBLESHOOTING.md\n'
fi
exit $fail
