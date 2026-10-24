#!/bin/bash
# Copies the signing engine from the iOS repository — the single source of signer.js — into the Android app, and pins
# its SHA-256. There is exactly one engine: never edit core/engine/src/main/assets/engine/signer.js by hand.
#
#   tools/engine/sync-from-ios.sh [path/to/bittool-signer-ios]     (default: ~/Documents/bitsigner-ios/BitSigner)
#
# It refuses to copy unless the iOS file matches the iOS repo's own engine/bundle-manifest.json, then updates
# EngineBundle.EXPECTED_SHA256 / EXPECTED_BYTES; EngineDigestTest and the runtime check enforce them.
# Then it syncs the translation catalogue the same way (tools/l10n/sync-from-ios.sh, which can also run on its own):
# the engine's English field labels and the app's interface strings are one catalogue, owned by the iOS repository.
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
# The engine source too, so anyone can rebuild signer.js from this repository alone (Open Source page, "Rebuild it
# yourself"). Only the output path of build.mjs differs from iOS; the bundle bytes do not depend on it.
rsync -a --delete --exclude node_modules "$IOS/engine/" "$ROOT/engine/"
sed -i '' -e "s#'../BitToolSigner/Resources/signer.js'#'../core/engine/src/main/assets/engine/signer.js'#g" \
  -e "s#file:'BitToolSigner/Resources/signer.js'#file:'core/engine/src/main/assets/engine/signer.js'#" "$ROOT/engine/build.mjs"
# The engine tests read the app's resources at iOS paths (audit BTS-06): here they are the Android assets, the same
# files (the data check compares them with iOS byte for byte). Without this, `npm test` failed one test and skipped the
# bundle test without loading any bundle.
sed -i '' -e "s#'../../BitToolSigner/Resources/signer.js'#'../../core/engine/src/main/assets/engine/signer.js'#g" \
  -e "s#'../../BitToolSigner/Resources/token-registry.json'#'../../core/data/src/main/assets/registry/token-registry.json'#g" \
  -e "s#'../../BitToolSigner/Resources/TokenIcons.index.json'#'../../core/data/src/main/assets/registry/TokenIcons.index.json'#g" \
  -e "s#'../../BitToolSigner/Resources/TokenIcons.pack'#'../../core/data/src/main/assets/registry/TokenIcons.pack'#g" \
  "$ROOT/engine/test/engine.test.mjs" "$ROOT/engine/test/chain-expansion.test.mjs"
if grep -rq "BitToolSigner/Resources" "$ROOT/engine/test"; then
  echo "engine/test still names an iOS resource path: extend the rewrite in $0" >&2
  exit 1
fi
echo "engine source synced from iOS into engine/ (rebuild: cd engine && npm ci --ignore-scripts && npm run build)"
"$ROOT/tools/l10n/sync-from-ios.sh" "$IOS"
echo "Next: tools/gradle-slot.sh ./gradlew :core:engine:testDebugUnitTest :core:engine:connectedDebugAndroidTest"
