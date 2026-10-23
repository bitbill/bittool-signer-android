#!/usr/bin/env bash
# Renders the Google Play listing images (en-US) from the HTML in this folder with headless Chrome:
#   icon.png 512 × 512, featureGraphic.png 1024 × 500, phoneScreenshots/1…8 1080 × 1920 (24-bit PNG, no alpha).
# Output: fastlane/metadata/android/en-US/images/ (fastlane `supply` layout, next to the listing text).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$ROOT/fastlane/metadata/android/en-US/images"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
mkdir -p "$OUT/phoneScreenshots"

render() { # page size output
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --allow-file-access-from-files --virtual-time-budget=4000 --window-size="$2" \
    --screenshot="$3" "file://$HERE/$1" >/dev/null 2>&1
  [ -s "$3" ] || { echo "render failed: $1" >&2; exit 1; }
  echo "$(basename "$3")"
}

render icon.html 512,512 "$OUT/icon.png"
render feature.html 1024,500 "$OUT/featureGraphic.png"
for n in 1 2 3 4 5 6 7 8; do
  render "screenshot.html?n=$n" 1080,1920 "$OUT/phoneScreenshots/${n}_en-US.png"
done
