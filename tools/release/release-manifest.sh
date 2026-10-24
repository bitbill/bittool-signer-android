#!/bin/sh
# Writes the public verification record of a release (docs/Reproducible-Build.md; the Android port of the iOS
# scripts/release-manifest.sh).
#
#     tools/release/release-manifest.sh [output-directory]       # default: release/
#
# It records what a third party needs in order to check that a published build was made from published source:
#
#   SHA256SUMS.txt          the SHA-256 of every file tracked by Git, in a fixed order (compare two with `diff`);
#   release-manifest.json   the commit, the version, the hash of SHA256SUMS.txt, the engine bundle hash as committed and
#                           as rebuilt from a clean dependency install in a throwaway copy of the commit, and, when
#                           tools/release/verify-release.sh has archived this commit's signed build, the APK and AAB
#                           hashes, the signing certificate and the engine hash read back out of the APK;
#   clean-install.log, clean-build.log   the full output of that engine rebuild.
#
# The working tree must be clean: a manifest describing uncommitted edits would name a commit nobody can check out.
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:-"$ROOT/release"}
BUNDLE=core/engine/src/main/assets/engine/signer.js

cd "$ROOT"
if [ -n "$(git status --porcelain)" ]; then
  echo "The working tree has uncommitted changes. Commit them first." >&2
  exit 1
fi
COMMIT=$(git rev-parse HEAD)
mkdir -p "$OUT"

# Every tracked file, hashed in a fixed order, so two people comparing two manifests compare the same list.
git ls-files -z | sort -z | xargs -0 shasum -a 256 > "$OUT/SHA256SUMS.txt"
MANIFEST_HASH=$(shasum -a 256 "$OUT/SHA256SUMS.txt" | cut -d' ' -f1)
SHIPPED=$(shasum -a 256 "$BUNDLE" | cut -d' ' -f1)
FILES=$(git ls-files | wc -l | tr -d ' ')
VERSION=$(sed -n 's/^val signerVersionName = "\(.*\)"$/\1/p' app/build.gradle.kts | head -1)

# Rebuild the engine from this exact commit in a scratch copy. `npm ci` installs the locked versions and
# `--ignore-scripts` keeps a dependency's install hook from touching the result.
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT HUP INT TERM
git archive --format=tar "$COMMIT" | tar -x -C "$WORK"
( cd "$WORK/engine" && npm ci --ignore-scripts >"$OUT/clean-install.log" 2>&1 && npm run build >"$OUT/clean-build.log" 2>&1 )
REBUILT=$(shasum -a 256 "$WORK/$BUNDLE" | cut -d' ' -f1)
REPRODUCED=false
[ "$SHIPPED" = "$REBUILT" ] && REPRODUCED=true

# The signed build of this commit, when verify-release.sh archived one (the newest such archive).
BUILD_MANIFEST=$(ls -t build/release/BitToolSigner-*/release-manifest.json 2>/dev/null | while read -r m; do
  grep -q "\"commit\": \"$COMMIT\"" "$m" && ! grep -q '"mode": "debug"' "$m" && { echo "$m"; break; }
done || true)

COMMIT="$COMMIT" VERSION="$VERSION" FILES="$FILES" MANIFEST_HASH="$MANIFEST_HASH" SHIPPED="$SHIPPED" REBUILT="$REBUILT" \
REPRODUCED="$REPRODUCED" BUILD_MANIFEST="$BUILD_MANIFEST" NODE="$(node --version)" NPM="$(npm --version)" \
OUT="$OUT" BUNDLE="$BUNDLE" python3 - <<'EOF'
import json, os
manifest = {
    'product': 'BitTool Signer for Android',
    'applicationId': 'com.bittool.signer',
    'version': os.environ['VERSION'],
    'commit': os.environ['COMMIT'],
    'trackedFiles': int(os.environ['FILES']),
    'trackedManifestSHA256': os.environ['MANIFEST_HASH'],
    'engineBundle': {
        'path': os.environ['BUNDLE'],
        'apkPath': 'assets/engine/signer.js',
        'shippedSHA256': os.environ['SHIPPED'],
        'rebuiltSHA256': os.environ['REBUILT'],
        'reproduced': os.environ['REPRODUCED'] == 'true',
        'node': os.environ['NODE'],
        'npm': os.environ['NPM'],
    },
}
build = os.environ['BUILD_MANIFEST']
if build:
    b = json.load(open(build))
    manifest['versionCode'] = b['versionCode']
    manifest['signing'] = {
        'certificateSHA256': b['signing']['apkCertificateSha256'],
        'playUploadCertificateSHA256': b['signing']['playCertificateSha256'],
    }
    manifest['engineBundle']['inApkSHA256'] = b['engineSha256']
    manifest['artifacts'] = {name: v for name, v in b['artifacts'].items() if name.endswith(('.apk', '.aab'))}
else:
    manifest['artifacts'] = None  # no signed build of this commit archived yet
with open(os.path.join(os.environ['OUT'], 'release-manifest.json'), 'w') as f:
    json.dump(manifest, f, indent=2)
    f.write('\n')
EOF

echo "commit     $COMMIT"
echo "files      $FILES (manifest $MANIFEST_HASH)"
echo "signer.js  $SHIPPED"
[ -n "$BUILD_MANIFEST" ] && echo "build      $(dirname "$BUILD_MANIFEST")" || echo "build      none archived for this commit (run tools/release/verify-release.sh --release first)"
if [ "$REPRODUCED" = true ]; then
  echo "rebuild    reproduces it exactly"
else
  echo "rebuild    DIFFERS: $REBUILT" >&2
  exit 1
fi
echo "written to $OUT"
