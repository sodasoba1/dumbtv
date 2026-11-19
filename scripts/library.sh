#!/usr/bin/env bash
# Safely deploy the custom Library banner: stage, verify, then atomically swap with rollback.
# Usage: ./scripts/library.sh [--dry-run] [-y|--yes] <folder>
set -euo pipefail
ADB=${ADB:-adb}
SERIAL=${SERIAL:-${ADB_SERIAL:-}}
DRY_RUN=0
ASSUME_YES=0
SRC=""
while [[ $# -gt 0 ]]; do
  case $1 in
    --dry-run) DRY_RUN=1; shift ;;
    -y|--yes) ASSUME_YES=1; shift ;;
    -*) echo "error: unknown option: $1" >&2; exit 1 ;;
    *) [[ -z $SRC ]] || { echo "error: give only one source folder" >&2; exit 1; }; SRC=$1; shift ;;
  esac
done
[[ -n $SRC ]] || { echo "usage: $0 [--dry-run] [-y|--yes] <folder>" >&2; exit 1; }
[[ -f $SRC/library.conf ]] || { echo "error: $SRC/library.conf not found" >&2; exit 1; }
adb_() { if [[ -n $SERIAL ]]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }
# Select one connected device, never silently deploy to an arbitrary device.
if [[ -z $SERIAL ]]; then
  devices=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
  count=$(printf '%s\n' "$devices" | awk 'NF {n++} END {print n+0}')
  [[ $count == 1 ]] || { echo "error: connect exactly one ADB device or set ADB_SERIAL" >&2; exit 1; }
  SERIAL=$(printf '%s\n' "$devices" | awk 'NF {print; exit}')
fi
[[ $(adb_ get-state 2>/dev/null || true) == device ]] || { echo "error: ADB device $SERIAL is not ready" >&2; exit 1; }
sdk=$(adb_ shell getprop ro.build.version.sdk | tr -d '\r')
[[ $sdk == 25 ]] || { echo "error: Library deployment supports only the AFTMM launcher target (API 25); this device reports API ${sdk:-unknown}" >&2; exit 1; }
adb_ shell pm path tv.dumbtv.home | grep -q '^package:' || { echo "error: DumbTV home is not installed on $SERIAL" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/tmp}/dumbtv-library.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
cp "$SRC/library.conf" "$WORK/library.conf"
seen="$WORK/.seen"
: > "$seen"
MAGICK=0; SIPS=0
command -v magick >/dev/null 2>&1 && MAGICK=1
command -v sips >/dev/null 2>&1 && SIPS=1
if [[ $MAGICK == 0 && $SIPS == 0 ]]; then
  echo "note: neither ImageMagick nor sips is installed; pictures will be uploaded uncropped"
fi
n=0
while IFS= read -r line || [[ -n $line ]]; do
  line=${line%%$'\r'}
  [[ -z ${line// } || $line == \#* ]] && continue
  img=$(printf '%s' "${line%%|*}" | xargs)
  case "$img" in ""|.|..|*/*|*\\*) echo "error: picture names must be simple file names: $img" >&2; exit 1 ;; esac
  if grep -qxF "$img" "$seen"; then
    echo "error: duplicate picture reference '$img' in library.conf" >&2; exit 1
  fi
  printf '%s\n' "$img" >> "$seen"
  [[ -f $SRC/$img ]] || { echo "error: missing picture: $img" >&2; exit 1; }
  out="$WORK/$img"
  cp "$SRC/$img" "$out"
  frac=0.15
  if [[ -f $SRC/crop.conf ]]; then
    f=$(awk -v n="$img" '$1 == n {print $2; exit}' "$SRC/crop.conf")
    [[ -z $f ]] || frac=$f
  fi
  awk -v f="$frac" 'BEGIN { if (f !~ /^[0-9]*([.][0-9]+)?$/ || f < 0 || f > 1) exit 1 }' ||
    { echo "error: crop position for $img must be a number from 0 to 1 (got '$frac')" >&2; exit 1; }
  if [[ $SIPS == 1 ]]; then
    w=$(sips -g pixelWidth "$out" | awk '/pixelWidth/ {print $2}')
    h=$(sips -g pixelHeight "$out" | awk '/pixelHeight/ {print $2}')
    if (( w * 500 >= h * 1920 )); then sips --resampleHeight 500 "$out" >/dev/null
    else sips --resampleWidth 1920 "$out" >/dev/null; fi
    w=$(sips -g pixelWidth "$out" | awk '/pixelWidth/ {print $2}')
    h=$(sips -g pixelHeight "$out" | awk '/pixelHeight/ {print $2}')
    y=$(awk -v h="$h" -v f="$frac" 'BEGIN {printf "%d", (h - 500) * f}')
    x=$(( (w - 1920) / 2 ))
    sips --cropToHeightWidth 500 1920 --cropOffset "$y" "$x" "$out" >/dev/null
  elif [[ $MAGICK == 1 ]]; then
    magick "$out" -resize '1920x500^' "$out.resized"
    dims=$(magick identify -format '%w %h' "$out.resized")
    read -r w h <<< "$dims"
    y=$(awk -v h="$h" -v f="$frac" 'BEGIN {printf "%d", (h - 500) * f}')
    x=$(( (w - 1920) / 2 ))
    magick "$out.resized" -crop "1920x500+$x+$y" +repage "$out"
    rm -f "$out.resized"
  fi
  printf '  %-40s ready (crop %s)\n' "$img" "$frac"
  n=$((n + 1))
done < "$WORK/library.conf"

DEST=/sdcard/Android/data/tv.dumbtv.home/files/library
suffix="${RANDOM}_$(date +%s)"
STAGE="$DEST.staging.$suffix"
BACKUP="$DEST.backup.$suffix"
echo "Library plan: $n picture(s) and library.conf -> $DEST"
[[ $MAGICK == 1 ]] && echo "Image processing: ImageMagick" || { [[ $SIPS == 1 ]] && echo "Image processing: sips" || echo "Image processing: none"; }
if [[ $DRY_RUN == 1 ]]; then
  echo "dry-run: no files uploaded and the existing Library is untouched"
  exit 0
fi
if [[ $ASSUME_YES != 1 ]]; then
  printf 'Replace the Library on %s? [y/N] ' "$SERIAL" >&2
  read -r answer
  case $answer in y|Y|yes|YES) ;; *) echo "cancelled" >&2; exit 1 ;; esac
fi

# Upload into a sibling directory first. Never remove the current Library until
# every file is uploaded and library.conf has been verified on the device.
adb_ shell "mkdir -p '$STAGE'"
for f in "$WORK"/*; do
  [[ $(basename "$f") == .seen ]] && continue
  if ! adb_ push "$f" "$STAGE/" >/dev/null; then
    adb_ shell "rm -rf '$STAGE'" >/dev/null 2>&1 || true
    echo "error: upload failed for $(basename "$f"); existing Library was left untouched" >&2
    exit 1
  fi
done
if ! adb_ shell "test -s '$STAGE/library.conf'"; then
  adb_ shell "rm -rf '$STAGE'" >/dev/null 2>&1 || true
  echo "error: uploaded Library configuration could not be verified" >&2
  exit 1
fi
if ! adb_ shell "if [ -d '$DEST' ]; then mv '$DEST' '$BACKUP' || exit 1; fi; if mv '$STAGE' '$DEST'; then rm -rf '$BACKUP'; else [ ! -d '$BACKUP' ] || mv '$BACKUP' '$DEST'; exit 1; fi"; then
  adb_ shell "rm -rf '$STAGE'" >/dev/null 2>&1 || true
  echo "error: could not activate the uploaded Library; the previous copy was restored where possible" >&2
  exit 1
fi
echo "pushed $n titles to $DEST"
adb_ shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || echo "note: return to DumbTV home to reload the banner"
echo "the banner reloads when the home screen comes back"
