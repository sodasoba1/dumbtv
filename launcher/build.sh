#!/usr/bin/env bash
# Builds launcher/build/dumbtv-home.apk from the bare SDK tools: no Gradle, no libraries.
# Needs JDK 17 and Android SDK platforms;android-$MIN_SDK plus build-tools.
# This APK is intentionally limited to Fire OS 6 / Android 7.1.2 (API 25).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# shellcheck disable=SC1091
source version.env
MIN_SDK=25

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $SDK ]]; then
  for d in /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk"; do
    if [[ -f $d/platforms/android-$MIN_SDK/android.jar ]]; then SDK=$d; break; fi
  done
fi
JAR=$SDK/platforms/android-$MIN_SDK/android.jar
BT_VERSION=${ANDROID_BUILD_TOOLS_VERSION:-}
if [[ -n $BT_VERSION ]]; then
  BT=$SDK/build-tools/$BT_VERSION
else
  BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1 || true)
fi
[[ -f $JAR ]] || { echo "error: $JAR missing — sdkmanager 'platforms;android-$MIN_SDK'" >&2; exit 1; }
[[ -x $BT/aapt2 && -x $BT/d8 && -x $BT/zipalign && -x $BT/apksigner ]] || {
  echo "error: incomplete Android build-tools at $BT — install build-tools;34.0.0" >&2
  exit 1
}

JDK17_PATHS=(/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/java-17-openjdk)
javac_major() { "$1/bin/javac" -version 2>&1 | sed -n 's/^javac \([0-9]*\).*/\1/p'; }

if [[ -z ${JAVA_HOME:-} || ! -x ${JAVA_HOME:-}/bin/javac ]]; then
  for j in "${JDK17_PATHS[@]}" /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
           "$(/usr/libexec/java_home 2>/dev/null || true)"; do
    if [[ -n $j && -x $j/bin/javac ]]; then JAVA_HOME=$j; break; fi
  done
fi
# build-tools 34's bundled d8 has failed with newer JDK class output; CI and releases use JDK 17.
if [[ -z ${JAVA_HOME:-} ]] || [[ $(javac_major "$JAVA_HOME") -ge 21 ]]; then
  for j in "${JDK17_PATHS[@]}"; do [[ -x $j/bin/javac ]] && { JAVA_HOME=$j; break; }; done
fi
[[ -x ${JAVA_HOME:-}/bin/javac ]] || {
  echo "error: no JDK 17 found — install JDK 17 (Temurin/OpenJDK)" >&2
  exit 1
}
if [[ $(javac_major "$JAVA_HOME") -ne 17 ]]; then
  echo "error: this reproducible build requires JDK 17; found JDK $(javac_major "$JAVA_HOME") at $JAVA_HOME" >&2
  exit 1
fi
export JAVA_HOME

KEYSTORE=${DUMBTV_KEYSTORE:-$PWD/release.keystore}
KEYPASS=${DUMBTV_KEYPASS:-dumbtvhome}
if [[ ! -f $KEYSTORE ]]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias home -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KEYPASS" -keypass "$KEYPASS" -dname "CN=dumbtv home" >/dev/null
  echo "created signing key $KEYSTORE (keep it: updates must be signed with the same key)"
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/dumbtv-home.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/gen" "$WORK/classes" build

"$BT/aapt2" compile --dir res -o "$WORK/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml "$WORK/res.zip" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version 25 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$WORK/gen" -o "$WORK/app.apk"

find src "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all -Xlint:-options -Werror -encoding UTF-8 \
  -classpath "$JAR" -d "$WORK/classes" @"$WORK/sources.txt"

find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --release --min-api "$MIN_SDK" --lib "$JAR" --output "$WORK" @"$WORK/classes.txt"
(cd "$WORK" && zip -q -X app.apk classes.dex)

"$BT/zipalign" -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --out build/dumbtv-home.apk "$WORK/aligned.apk"
"$BT/apksigner" verify --verbose build/dumbtv-home.apk

perms=$("$BT/aapt2" dump permissions build/dumbtv-home.apk | grep -c 'uses-permission' || true)
if command -v sha256sum >/dev/null; then
  sha256sum build/dumbtv-home.apk | awk '{print $1}' > build/dumbtv-home.apk.sha256
else
  shasum -a 256 build/dumbtv-home.apk | awk '{print $1}' > build/dumbtv-home.apk.sha256
fi
printf 'built build/dumbtv-home.apk  %s bytes  %s uses-permission entries\n' \
  "$(wc -c < build/dumbtv-home.apk | tr -d ' ')" "$perms"
