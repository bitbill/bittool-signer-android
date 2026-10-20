#!/bin/bash
# Copies the signing engine from the iOS repository — the single source of signer.js — into the Android app, and pins
# its SHA-256. There is exactly one engine: never edit core/engine/src/main/assets/engine/signer.js by hand.
#
#   tools/engine/sync-from-ios.sh [path/to/bittool-signer-ios]     (default: ~/Documents/bitsigner-ios/BitSigner)
#
# It refuses to copy unless the iOS file matches the iOS repo's own engine/bundle-manifest.json, then updates
# EngineBundle.EXPECTED_SHA256 / EXPECTED_BYTES; EngineDigestTest and the runtime check enforce them.
set -euo pipefail
IOS=${1:-$HOME/Documents/bitsigner-ios/BitSigner}
SRC="$IOS/BitToolSigner/Resources/signer.js"
MANIFEST="$IOS/engine/bundle-manifest.json"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DST="$ROOT/core/engine/src/main/assets/engine/signer.js"
KT="$ROOT/core/engine/src/main/kotlin/com/bittool/signer/core/engine/internal/EngineBundle.kt"

sha=$(shasum -a 256 "$SRC" | cut -d' ' -f1)
bytes=$(wc -c < "$SRC" | tr -d ' ')
want=$(python3 -c "import json,sys; m=json.load(open(sys.argv[1])); print(m['sha256'], m['bytes'])" "$MANIFEST")
if [ "$sha $bytes" != "$want" ]; then
  echo "iOS signer.js ($sha, $bytes bytes) does not match $MANIFEST ($want). Rebuild the iOS engine first." >&2
  exit 1
fi
cp "$SRC" "$DST"
grouped=$(python3 -c "import sys; print(f'{int(sys.argv[1]):_}')" "$bytes")
sed -i '' -E "s/(const val EXPECTED_SHA256 = \")[0-9a-f]{64}(\")/\1$sha\2/; s/(const val EXPECTED_BYTES = )[0-9_]+/\1$grouped/" "$KT"
cmp "$SRC" "$DST"
echo "signer.js synced from iOS: $sha ($bytes bytes)"
echo "Next: tools/gradle-slot.sh ./gradlew :core:engine:testDebugUnitTest :core:engine:connectedDebugAndroidTest"
