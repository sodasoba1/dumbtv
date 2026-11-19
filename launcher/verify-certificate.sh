#!/usr/bin/env bash
# Verify the signer identity of a downloaded release APK before installing it.
# Prefer apksigner (full APK signing-scheme verification); otherwise verify the
# JAR/v1 CMS signer. Android's PackageManager performs the full APK verification
# again during install, including v2/v3 signing blocks.
set -euo pipefail

APK=${1:-}
[[ -n $APK && -s $APK ]] || { echo "error: APK file not found or empty: $APK" >&2; exit 1; }
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
EXPECTED=$(tr -d '[:space:]' < "$HERE/release-certificate.sha256")
[[ $EXPECTED =~ ^[0-9a-f]{64}$ ]] || {
  echo "error: trusted release certificate pin is missing or invalid" >&2
  exit 1
}

APKSIGNER=${APKSIGNER:-}
if [[ -z $APKSIGNER ]]; then
  APKSIGNER=$(command -v apksigner 2>/dev/null || true)
fi

if [[ -n $APKSIGNER ]]; then
  output=$("$APKSIGNER" verify --verbose --print-certs "$APK") || {
    echo "error: apksigner could not verify the release APK" >&2
    exit 1
  }
  digests=$(printf '%s\n' "$output" |
    sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' |
    tr 'A-F' 'a-f')
  count=$(printf '%s\n' "$digests" | awk 'NF {n++} END {print n+0}')
  [[ $count == 1 ]] || {
    echo "error: expected one APK signer, found $count" >&2
    exit 1
  }
  ACTUAL=$digests
else
  command -v unzip >/dev/null 2>&1 && command -v openssl >/dev/null 2>&1 || {
    echo "error: release signer verification needs apksigner, or both unzip and openssl" >&2
    exit 1
  }
  WORK=$(mktemp -d "${TMPDIR:-/tmp}/dumbtv-signer.XXXXXX")
  trap 'rm -rf "$WORK"' EXIT
  entries=$(unzip -Z1 "$APK" 2>/dev/null |
    awk 'toupper($0) ~ /^META-INF\/[^/]+\.(RSA|DSA|EC)$/ {print}')
  count=$(printf '%s\n' "$entries" | awk 'NF {n++} END {print n+0}')
  [[ $count == 1 ]] || {
    echo "error: no unique JAR signature block found; install Android build-tools apksigner and retry" >&2
    exit 1
  }
  signature=${entries%%$'\n'*}
  signature_base=${signature%.*}
  sf_entry=$(unzip -Z1 "$APK" |
    awk -v wanted="${signature_base}.SF" 'toupper($0) == toupper(wanted) {print; exit}')
  [[ -n $sf_entry ]] || {
    echo "error: APK signature file is missing; refusing to install" >&2
    exit 1
  }
  unzip -p "$APK" "$signature" > "$WORK/signature.der"
  unzip -p "$APK" "$sf_entry" > "$WORK/signature.sf"
  openssl cms -verify -inform DER -in "$WORK/signature.der" \
    -content "$WORK/signature.sf" -noverify -out /dev/null \
    -signer "$WORK/signer.pem" >/dev/null 2>&1 || {
      echo "error: APK JAR signature could not be verified; refusing to install" >&2
      exit 1
    }
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(openssl x509 -in "$WORK/signer.pem" -outform DER | sha256sum | awk '{print $1}')
  else
    ACTUAL=$(openssl x509 -in "$WORK/signer.pem" -outform DER | shasum -a 256 | awk '{print $1}')
  fi
fi

[[ $ACTUAL == "$EXPECTED" ]] || {
  echo "error: release APK signer does not match the trusted DumbTV certificate" >&2
  echo "  expected SHA-256: $EXPECTED" >&2
  echo "  actual SHA-256:   $ACTUAL" >&2
  echo "not installing this APK" >&2
  exit 1
}
echo "release certificate verified ($ACTUAL)"
