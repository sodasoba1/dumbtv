#!/usr/bin/env bash
# Checks the local launcher build or the release published by this fork.
#   ./launcher/release-check.sh            local build + remote release
#   ./launcher/release-check.sh --local    only launcher/build/dumbtv-home.apk
#   ./launcher/release-check.sh --remote   only the GitHub release
#   ./launcher/release-check.sh --manifest only launcher/version.env
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
REPO=https://github.com/sodasoba1/dumbtv/releases/download

mode=${1:-all}
case $mode in
  all|--local|--remote|--manifest) ;;
  *) echo "usage: $0 [--local|--remote|--manifest]" >&2; exit 2 ;;
esac
[[ $mode == all ]] && mode=both

fail=0
ok()  { printf '  ok    %s\n' "$*"; }
bad() { printf '  FAIL  %s\n' "$*"; fail=1; }

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

[[ -f version.env ]] || { echo "error: launcher/version.env missing" >&2; exit 1; }
# shellcheck disable=SC1091
source version.env
echo "version $VERSION"

[[ $VERSION =~ ^[0-9]+\.[0-9]+$ ]] || bad "VERSION '$VERSION' is not <major>.<minor>"
[[ $VERSION_CODE =~ ^[0-9]+$ ]] || bad "VERSION_CODE is not numeric"
[[ $VERSION_NAME == "$VERSION" ]] || bad "VERSION_NAME must match VERSION"
[[ $mode == --manifest ]] && { [[ $fail == 0 ]] && echo "manifest consistent"; exit "$fail"; }

if [[ $mode == both || $mode == --local ]]; then
  APK=build/dumbtv-home.apk
  SUM=build/dumbtv-home.apk.sha256
  if [[ -f $APK && -f $SUM ]]; then
    expected=$(tr -d '[:space:]' < "$SUM")
    got=$(sha256 "$APK")
    [[ $expected =~ ^[0-9a-f]{64}$ && $got == "$expected" ]] \
      && ok "$APK matches its build checksum" || bad "$APK does not match $SUM"
  else
    [[ $mode == --local ]] && bad "build APK/checksum missing (run ./build.sh)" \
      || echo "  note  local build not present, skipping"
  fi
fi

if [[ $mode == both || $mode == --remote ]]; then
  BASE=$REPO/launcher-v$VERSION
  tmp=$(mktemp -d)
  if curl -fsSL "$BASE/dumbtv-home.apk.sha256" -o "$tmp/dumbtv-home.apk.sha256" \
      && curl -fsSL "$BASE/dumbtv-home.apk" -o "$tmp/dumbtv-home.apk"; then
    expected=$(tr -d '[:space:]' < "$tmp/dumbtv-home.apk.sha256")
    got=$(sha256 "$tmp/dumbtv-home.apk")
    [[ $expected =~ ^[0-9a-f]{64}$ && $got == "$expected" ]] \
      && ok "fork release launcher-v$VERSION matches its published checksum" \
      || bad "fork release APK does not match its SHA-256 sidecar"
  else
    bad "fork release launcher-v$VERSION or its checksum sidecar is not reachable"
  fi
  rm -rf "$tmp"
fi

if [[ $fail == 0 ]]; then
  echo "release consistent"
else
  echo
  echo "fix: build and publish dumbtv-home.apk plus dumbtv-home.apk.sha256"
  echo "     to https://github.com/sodasoba1/dumbtv/releases/tag/launcher-v$VERSION"
fi
exit "$fail"
