#!/usr/bin/env bash
# Verify the complete APK with Android apksigner and pin its signer certificate.
# No JAR/v1 or META-INF fallback: apksigner reads v2/v3 signer identity from the APK.
set -euo pipefail
APK=${1:-}
[[ -n $APK && -s $APK ]] || { echo "error: APK file not found or empty: $APK" >&2; exit 1; }
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
EXPECTED=$(< "$HERE/signing.sha256")
[[ $EXPECTED =~ ^[0-9a-f]{64}$ ]] || { echo "error: trusted release signer pin is missing or invalid" >&2; exit 1; }

# Git Bash aliases are interactive-shell conveniences and are not visible to
# this script. Discover the SDK's real executable, including Windows .bat.
APKSIGNER=""
APKSIGNER_KIND=""
sdk_roots=()
[[ -n ${ANDROID_HOME:-} ]] && sdk_roots+=("$ANDROID_HOME")
[[ -n ${ANDROID_SDK_ROOT:-} ]] && sdk_roots+=("$ANDROID_SDK_ROOT")
if [[ -n ${LOCALAPPDATA:-} ]] && command -v cygpath >/dev/null 2>&1; then
  sdk_roots+=("$(cygpath -u "$LOCALAPPDATA")/Android/Sdk")
fi

for sdk in "${sdk_roots[@]}"; do
  [[ -n $sdk ]] || continue
  native="$sdk/build-tools/34.0.0/apksigner"
  windows="$sdk/build-tools/34.0.0/apksigner.bat"
  if [[ -x $native ]]; then
    APKSIGNER=$native
    APKSIGNER_KIND=native
    break
  elif [[ -f $windows ]] && command -v cmd.exe >/dev/null 2>&1 && command -v cygpath >/dev/null 2>&1; then
    APKSIGNER=$windows
    APKSIGNER_KIND=windows-bat
    break
  fi
done

if [[ -z $APKSIGNER ]]; then
  path_signer=$(command -v apksigner 2>/dev/null || true)
  # Ignore aliases/functions: command -v can return their definitions, not a file.
  if [[ -n $path_signer && -f $path_signer && -x $path_signer ]]; then
    APKSIGNER=$path_signer
    APKSIGNER_KIND=native
  fi
fi

[[ -n $APKSIGNER ]] || {
  echo "error: apksigner not found; install Android SDK build-tools 34.0.0 or add apksigner to PATH" >&2
  echo "  On Windows Git Bash, set ANDROID_HOME or install the SDK under LOCALAPPDATA." >&2
  exit 1
}

if [[ $APKSIGNER_KIND == windows-bat ]]; then
  win_signer=$(cygpath -w "$APKSIGNER")
  win_apk=$(cygpath -w "$APK")
  # cmd /s /c needs an extra outer quote pair so paths with spaces survive.
  command_line="\"\"$win_signer\" verify --print-certs \"$win_apk\"\""
  output=$(cmd.exe //d //s //c "$command_line" 2>&1) || {
    echo "error: apksigner could not verify the downloaded release APK" >&2
    printf '%s\n' "$output" >&2
    echo "not installing this APK" >&2
    exit 1
  }
else
  output=$("$APKSIGNER" verify --print-certs "$APK" 2>&1) || {
    echo "error: apksigner could not verify the downloaded release APK" >&2
    printf '%s\n' "$output" >&2
    echo "not installing this APK" >&2
    exit 1
  }
fi

digests=$(printf '%s\n' "$output" | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' | tr 'A-F' 'a-f')
count=$(printf '%s\n' "$digests" | awk 'NF {n++} END {print n+0}')
[[ $count == 1 ]] || { echo "error: expected exactly one APK signer certificate, found $count" >&2; echo "not installing this APK" >&2; exit 1; }
ACTUAL=$digests
[[ $ACTUAL == "$EXPECTED" ]] || {
  echo "error: release APK signer does not match the trusted DumbTV certificate" >&2
  echo "  expected SHA-256: $EXPECTED" >&2
  echo "  actual SHA-256:   $ACTUAL" >&2
  echo "not installing this APK" >&2
  exit 1
}
echo "release certificate verified ($ACTUAL)"
