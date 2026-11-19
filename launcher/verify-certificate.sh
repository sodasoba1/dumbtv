#!/usr/bin/env bash
# Verify the complete APK with Android apksigner and pin its signer certificate.
# No JAR/v1 or META-INF fallback: apksigner reads v2/v3 signer identity from the APK.
set -euo pipefail
APK=${1:-}
[[ -n $APK && -s $APK ]] || { echo "error: APK file not found or empty: $APK" >&2; exit 1; }
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
EXPECTED=$(< "$HERE/signing.sha256")
[[ $EXPECTED =~ ^[0-9a-f]{64}$ ]] || { echo "error: trusted release signer pin is missing or invalid" >&2; exit 1; }
APKSIGNER=""
if [[ -n ${ANDROID_HOME:-} && -x "$ANDROID_HOME/build-tools/34.0.0/apksigner" ]]; then
  APKSIGNER=$ANDROID_HOME/build-tools/34.0.0/apksigner
else
  APKSIGNER=$(command -v apksigner 2>/dev/null || true)
fi
[[ -n $APKSIGNER ]] || {
  echo "error: apksigner not found; install Android SDK build-tools 34.0.0 or add apksigner to PATH" >&2
  echo "  Alternatively, use --apk FILE only after manually checking its signer fingerprint against README.md." >&2
  exit 1
}
output=$("$APKSIGNER" verify --print-certs "$APK" 2>&1) || {
  echo "error: apksigner could not verify the downloaded release APK" >&2
  printf '%s\n' "$output" >&2
  echo "not installing this APK" >&2
  exit 1
}
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
