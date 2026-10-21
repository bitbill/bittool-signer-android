#!/bin/bash
# BitTool Signer release verification (docs/release.md): builds both release flavors and runs every automated check,
# then prints a pass/fail summary.
#
#   tools/release/verify-release.sh                  # any machine: debug-signed when no release key is configured
#   tools/release/verify-release.sh --release        # the release candidate: release key, clean tree, iOS repo required
#   tools/release/verify-release.sh --checks-only    # re-run the artifact checks on the existing outputs (no Gradle)
#
# Options:
#   --release          strict mode: a missing release key, a dirty tree, a missing iOS repository or a skipped step
#                      fails instead of warning.
#   --checks-only      skip the three Gradle steps (build, unit tests, lint) and check the artifacts already built
#                      (archived under a separate -checks-only name).
#   --build-number N   re-issue the same versionName with BUILD = N (-Psigner.buildNumber=N, default 1).
#   --ios PATH|none    the iOS repository the engine and data are compared with (default $SIGNER_IOS_REPO or
#                      ~/Documents/bitsigner-ios/BitSigner); "none" compares with the Android pins only. A path
#                      without engine/bundle-manifest.json is a usage error.
#
# What it does, in order (each line of the summary is one of these):
#   1. Git: commit, clean tree, tag android-v<versionName>.  2. Signing mode: release key / Play upload key / DEBUG.
#   3. l10n: iOS catalogue import up to date, Android-only strings translated into the 20 other languages, importer
#      tests, catalogue copies byte-identical with the iOS repository.
#   4. Gradle build of :app:assembleApkRelease :app:assemblePlayRelease :app:bundlePlayRelease (R8, resource shrinking;
#      the build itself enforces the merged-manifest permission allowlist, the component allowlist and "no debug
#      harness in a release"); without a release key it adds -Psigner.allowDebugSigning=true and every output is
#      labelled DEBUG-SIGNED.
#   5. Unit tests of every module (testDebugUnitTest, :core:model:test, :app:test{Apk,Play}DebugUnitTest).
#   6. Lint of both release variants (checkDependencies: every module).
#   7. Artifact checks on app-apk-release.apk, app-play-release.apk and app-play-release.aab (its base module in APK
#      layout): version, SDK levels and manifest flags (not debuggable, no backup), signatures and certificate
#      SHA-256, offline (check-offline.sh), 16 KB alignment of every .so (check-16kb.py, zipalign -P 16), engine digest
#      from the artifact = EngineBundle pin = iOS, data assets = iOS resources and the flavor's legal texts,
#      permissions = gaps.md G8 set, components and harness reports, assets listing, third-party notices, sizes.
#   8. Archive in build/release/BitToolSigner-<versionName>-<versionCode>[-DEBUG-SIGNED][-checks-only]/: the APK and
#      AAB, both R8 mapping.txt files and native debug symbols, SHA256SUMS, release-manifest.json, tracked-files.txt,
#      the logs and this summary; and the AAB carries the same R8 mapping and native symbol tables for Play.
# Exit status 0 when no check failed (warnings allowed outside --release), 1 otherwise, 2 for a usage error.
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT" || exit 2

STRICT=0
GRADLE_STEPS=1
BUILD_NUMBER=""
IOS=${SIGNER_IOS_REPO:-$HOME/Documents/bitsigner-ios/BitSigner}
while [ $# -gt 0 ]; do
  case "$1" in
    --release) STRICT=1 ;;
    --checks-only) GRADLE_STEPS=0 ;;
    --build-number) BUILD_NUMBER=${2:-}; shift ;;
    --ios) IOS=${2:-}; shift ;;
    -h|--help) sed -n '2,/^set -u/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done
case "$BUILD_NUMBER" in ''|[0-9]|[0-9][0-9]) ;; *) echo "--build-number must be 0-99" >&2; exit 2 ;; esac
[ "$IOS" = none ] && IOS=""
# A wrong or missing path is a usage error, not a pile of engine/data failures: name it, or say "--ios none".
if [ -n "$IOS" ] && [ ! -f "$IOS/engine/bundle-manifest.json" ]; then
  echo "no iOS repository at $IOS (no engine/bundle-manifest.json): pass --ios PATH, set SIGNER_IOS_REPO, or --ios none" >&2
  exit 2
fi

# ---------------------------------------------------------------------------------------------------------- tools
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export ANDROID_HOME=$SDK
BUILD_TOOLS=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
BUILD_TOOLS=${BUILD_TOOLS%/}
AAPT2=$BUILD_TOOLS/aapt2
APKSIGNER=$BUILD_TOOLS/apksigner
ZIPALIGN=$BUILD_TOOLS/zipalign
: "${JAVA_HOME:=$HOME/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
for tool in "$AAPT2" "$APKSIGNER" "$ZIPALIGN" "$JAVA_HOME/bin/keytool" "$JAVA_HOME/bin/jarsigner"; do
  [ -x "$tool" ] || { echo "missing tool: $tool (Android SDK build-tools, JDK)" >&2; exit 2; }
done
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }
# -I: isolated (no user site, no PYTHON* variables); -B: the l10n tools import each other, and no __pycache__ may
# appear in the source tree (the tree must stay clean).
PY="python3 -I -B"

# ---------------------------------------------------------------------------------------------------------- facts
VERSION_NAME=$(sed -nE 's/^val signerVersionName = "([0-9]+\.[0-9]+\.[0-9]+)".*/\1/p' app/build.gradle.kts)
DEFAULT_BUILD=$(sed -nE 's/^val signerBuildNumber = .*\?: ([0-9]+)$/\1/p' app/build.gradle.kts)
[ -n "$VERSION_NAME" ] && [ -n "$DEFAULT_BUILD" ] || { echo "cannot read the version from app/build.gradle.kts" >&2; exit 2; }
BUILD=${BUILD_NUMBER:-$DEFAULT_BUILD}
VERSION_CODE=$(echo "$VERSION_NAME" | awk -F. -v b="$BUILD" '{ print $1 * 1000000 + $2 * 10000 + $3 * 100 + b }')
ENGINE_KT=core/engine/src/main/kotlin/com/bittool/signer/core/engine/internal/EngineBundle.kt
PIN_SHA=$(sed -nE 's/.*const val EXPECTED_SHA256 = "([0-9a-f]{64})".*/\1/p' "$ENGINE_KT")
PIN_BYTES=$(sed -nE 's/.*const val EXPECTED_BYTES = ([0-9_]+).*/\1/p' "$ENGINE_KT" | tr -d _)
[ -n "$PIN_SHA" ] && [ -n "$PIN_BYTES" ] || { echo "cannot read the engine pin from $ENGINE_KT" >&2; exit 2; }
# A source export (git archive, a copied directory, also one inside another repository) has no commit of its own to
# record or tag: never a release (the git line warns).
IN_GIT=0
toplevel=$(git rev-parse --show-toplevel 2>/dev/null) && [ "$(cd "$toplevel" && pwd -P)" = "$(pwd -P)" ] && IN_GIT=1
COMMIT=unknown
[ $IN_GIT = 1 ] && COMMIT=$(git rev-parse HEAD 2>/dev/null || echo unknown)
TAG="android-v$VERSION_NAME"

# gaps.md G8 / app/build.gradle.kts check*Permissions: the exact set every release variant must request.
EXPECTED_PERMISSIONS="android.permission.CAMERA
android.permission.DETECT_SCREEN_CAPTURE
android.permission.DETECT_SCREEN_RECORDING
android.permission.HIDE_OVERLAY_WINDOWS
android.permission.USE_BIOMETRIC
android.permission.USE_FINGERPRINT
com.bittool.signer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
# Size budgets (bytes). They catch an accidental dependency or asset; raise them deliberately, in this file.
# 1.0.0: APK 20.7 MB (TokenIcons.pack alone is 11.2 MB), AAB 24.2 MB.
MAX_APK_BYTES=${SIGNER_MAX_APK_BYTES:-26000000}
MAX_AAB_BYTES=${SIGNER_MAX_AAB_BYTES:-30000000}

# Signing secrets come from local.properties or the environment, as app/build.gradle.kts reads them (values are never
# printed). Prints the four values' presence as "set" / "".
secret() {
  local key=$1 env value=""
  env=$(printf '%s' "$key" | tr '[:lower:].' '[:upper:]_')
  if [ -f local.properties ]; then
    value=$(grep -E "^[[:space:]]*${key//./\\.}[[:space:]]*[=:]" local.properties | tail -1 | sed -E 's/^[^=:]*[=:]//')
  fi
  [ -n "$(printf '%s' "$value" | tr -d '[:space:]')" ] || value=$(printenv "$env" || true)
  [ -n "$(printf '%s' "$value" | tr -d '[:space:]')" ] && echo set
}
count_keys() {
  local prefix=$1 n=0 k
  for k in storeFile storePassword keyAlias keyPassword; do [ -n "$(secret "$prefix.$k")" ] && n=$((n + 1)); done
  echo $n
}
RELEASE_KEYS=$(count_keys signer.release)
UPLOAD_KEYS=$(count_keys signer.upload)
GRADLE_PROPS=""
[ -n "$BUILD_NUMBER" ] && GRADLE_PROPS="-Psigner.buildNumber=$BUILD_NUMBER"
if [ "$RELEASE_KEYS" = 4 ]; then
  SIGNING=release
  SIGNING_LABEL="release key"
  [ "$UPLOAD_KEYS" = 4 ] && SIGNING_LABEL="release key (apk) + Play upload key (play)"
else
  SIGNING=debug
  SIGNING_LABEL="DEBUG-SIGNED (no release key) - NOT FOR DISTRIBUTION"
  GRADLE_PROPS="$GRADLE_PROPS -Psigner.allowDebugSigning=true"
fi
NAME="BitToolSigner-$VERSION_NAME-$VERSION_CODE"
[ $SIGNING = debug ] && NAME="$NAME-DEBUG-SIGNED"
# A --checks-only run never replaces the archive of a full run (its Gradle logs, the summary that passed --release).
OUT=build/release/$NAME
[ $GRADLE_STEPS = 0 ] && OUT="$OUT-checks-only"
rm -rf "$OUT"
mkdir -p "$OUT/logs" "$OUT/work"
LOGS=$OUT/logs

# ---------------------------------------------------------------------------------------------------------- results
N=0
FAILS=0
WARNS=0
SKIPS=0
: > "$OUT/work/results"
# record STATUS NAME DETAIL   (STATUS: PASS FAIL WARN SKIP INFO)
record() {
  local status=$1
  if [ "$status" = WARN ] && [ $STRICT = 1 ]; then status=FAIL; fi
  if [ "$status" = SKIP ] && [ $STRICT = 1 ]; then status=FAIL; fi
  case $status in FAIL) FAILS=$((FAILS + 1)) ;; WARN) WARNS=$((WARNS + 1)) ;; SKIP) SKIPS=$((SKIPS + 1)) ;; esac
  N=$((N + 1))
  printf '%-4s  %-42s %s\n' "$status" "$2" "$(printf '%s' "$3" | cut -c1-220)" >> "$OUT/work/results"
  printf '[verify] %-4s %s: %s\n' "$status" "$2" "$3"
}
# check NAME LOG COMMAND...: runs COMMAND with its output in LOG; PASS when it exits 0. The detail is the last line.
check() {
  local name=$1 log=$2
  shift 2
  if "$@" > "$log" 2>&1; then
    record PASS "$name" "$(tail -1 "$log")"
  else
    local why
    why=$(grep -E 'BAD|FORBIDDEN|ERROR|FAIL|differ|missing|stale|rror' "$log" | head -1 | cut -c1-160)
    record FAIL "$name" "${why:-$(tail -1 "$log" | cut -c1-160)} (log: $log)"
  fi
}
gradle() {
  local log=$1
  shift
  echo "[verify] ./gradlew $* (log: $log)"
  # shellcheck disable=SC2086
  tools/gradle-slot.sh ./gradlew "$@" $GRADLE_PROPS > "$log" 2>&1
}
# The reason Gradle gives for a failed build: the two lines after "* What went wrong:" (the task and its message),
# else the first compiler error or failed task.
gradle_why() {
  local why
  why=$(awk '/^\* What went wrong:/ { getline a; getline b; print a " " b; exit }' "$1")
  [ -n "$why" ] || why=$(grep -E '^e: |FAILED' "$1" | head -2 | tr '\n' ' ')
  printf '%s' "$why" | cut -c1-200
}
size_of() { wc -c < "$1" | tr -d ' '; }
mb() { awk -v b="$1" 'BEGIN { printf "%.1f MB", b / 1000000 }'; }

echo "[verify] BitTool Signer $VERSION_NAME ($VERSION_CODE), commit ${COMMIT:0:12}, signing: $SIGNING_LABEL"
echo "[verify] output: $OUT"

# 1. Git ----------------------------------------------------------------------------------------------------------
if [ $IN_GIT = 0 ]; then
  dirty="not a git checkout"
  record WARN "git: clean tree" "not the top of a git checkout (an export or a copy?): no commit to record, the tree cannot be checked"
elif ! dirty=$(git status --porcelain 2>&1); then
  record WARN "git: clean tree" "git status failed: $(echo "$dirty" | head -1 | cut -c1-120)"
  dirty="git status failed"
else
  dirty=$(printf '%s\n' "$dirty" | grep -v '^?? build/' | head -5)
  if [ -z "$dirty" ]; then record PASS "git: clean tree" "commit $COMMIT"
  else record WARN "git: clean tree" "uncommitted changes: $(echo "$dirty" | tr '\n' ' ' | cut -c1-120)"; fi
fi
if [ "$(git rev-parse -q --verify "refs/tags/$TAG^{commit}" 2>/dev/null)" = "$COMMIT" ]; then
  record PASS "git: tag $TAG" "on HEAD"
else
  record INFO "git: tag $TAG" "not on HEAD yet (tag the commit that passed --release)"
fi
# The versionName follows the iOS MARKETING_VERSION (DECISIONS §1, ownbit-build-release §3.2).
PBXPROJ=${IOS:+$IOS/BitToolSigner.xcodeproj/project.pbxproj}
if [ -n "$PBXPROJ" ] && [ -f "$PBXPROJ" ]; then
  ios_version=$(sed -nE 's/^[[:space:]]*MARKETING_VERSION = ([0-9.]+);$/\1/p' "$PBXPROJ" | sort -u | tr '\n' ' ' | sed 's/ $//')
  ios_build=$(sed -nE 's/^[[:space:]]*CURRENT_PROJECT_VERSION = ([0-9]+);$/\1/p' "$PBXPROJ" | sort -u | tr '\n' ' ' | sed 's/ $//')
  if [ "$ios_version" = "$VERSION_NAME" ]; then
    record PASS "version: $VERSION_NAME ($VERSION_CODE)" "= iOS MARKETING_VERSION $ios_version; BUILD $BUILD (iOS CURRENT_PROJECT_VERSION $ios_build)"
  else
    record WARN "version: $VERSION_NAME ($VERSION_CODE)" "iOS MARKETING_VERSION is $ios_version: confirm the Android version is meant to differ"
  fi
else
  record WARN "version: $VERSION_NAME ($VERSION_CODE)" "$([ -n "$IOS" ] && echo "no $PBXPROJ" || echo "--ios none"): versionName not compared with iOS"
fi

# 2. Signing mode ---------------------------------------------------------------------------------------------------
if [ "$RELEASE_KEYS" != 0 ] && [ "$RELEASE_KEYS" != 4 ]; then
  record FAIL "signing: release key" "signer.release.* only partially configured ($RELEASE_KEYS of 4); the build refuses it"
elif [ $SIGNING = release ]; then
  record PASS "signing: release key" "$SIGNING_LABEL"
else
  record WARN "signing: release key" "$SIGNING_LABEL"
fi
if [ "$UPLOAD_KEYS" != 0 ] && [ "$UPLOAD_KEYS" != 4 ]; then
  record FAIL "signing: Play upload key" "signer.upload.* only partially configured ($UPLOAD_KEYS of 4)"
elif [ "$UPLOAD_KEYS" = 4 ] && [ $SIGNING = debug ]; then
  record FAIL "signing: Play upload key" "an upload key without a release key: play would be upload-signed and apk debug-signed"
elif [ "$UPLOAD_KEYS" = 4 ]; then
  record PASS "signing: Play upload key" "play is signed with the upload key (Play App Signing re-signs it)"
else
  record INFO "signing: Play upload key" "not configured: play is signed like apk"
fi

# 3. l10n ----------------------------------------------------------------------------------------------------------
check "l10n: iOS catalogue import up to date" "$LOGS/l10n-import.log" $PY tools/l10n/import_ios_strings.py --check
check "l10n: Android-only strings translated" "$LOGS/l10n-android.log" $PY tools/l10n/android_strings.py check
check "l10n: importer tests" "$LOGS/l10n-tests.log" python3 -B -m unittest discover -s tools/l10n -p 'test_*.py'
if [ -n "$IOS" ] && [ -d "$IOS/translations" ]; then
  stale=""
  for f in tools/l10n/translations/*.json; do
    cmp -s "$f" "$IOS/translations/$(basename "$f")" || stale="$stale $(basename "$f")"
  done
  for f in "$IOS"/translations/*.json; do
    [ -f "tools/l10n/translations/$(basename "$f")" ] || stale="$stale $(basename "$f")(missing)"
  done
  cmp -s tools/l10n/interface-strings.json "$IOS/engine/data/interface-strings.json" || stale="$stale interface-strings.json"
  if [ -z "$stale" ]; then
    record PASS "l10n: catalogue copies = iOS" "$(ls tools/l10n/translations/*.json | wc -l | tr -d ' ') translations + interface-strings.json byte-identical"
  else
    record FAIL "l10n: catalogue copies = iOS" "differ from $IOS:$stale (copy them, then rerun import_ios_strings.py)"
  fi
else
  record WARN "l10n: catalogue copies = iOS" "$([ -n "$IOS" ] && echo "no $IOS/translations" || echo "--ios none"): catalogue copies not compared"
fi

# 4.-6. Gradle ------------------------------------------------------------------------------------------------------
APK_A=app/build/outputs/apk/apk/release/app-apk-release.apk
APK_P=app/build/outputs/apk/play/release/app-play-release.apk
AAB=app/build/outputs/bundle/playRelease/app-play-release.aab
BUILD_OK=1
if [ $GRADLE_STEPS = 1 ]; then
  start=$(date +%s)
  if gradle "$LOGS/gradle-build.log" :app:assembleApkRelease :app:assemblePlayRelease :app:bundlePlayRelease; then
    modes=$(grep -E '^BitTool Signer release:' "$LOGS/gradle-build.log" | sort -u | sed 's/^BitTool Signer release: //' | tr '\n' ' ')
    record PASS "gradle: build apkRelease + playRelease" "$(( $(date +%s) - start )) s; ${modes:-signing mode not logged (up to date)}"
  else
    BUILD_OK=0
    record FAIL "gradle: build apkRelease + playRelease" "$(gradle_why "$LOGS/gradle-build.log") (log: $LOGS/gradle-build.log)"
  fi
  start=$(date +%s)
  gradle "$LOGS/gradle-tests.log" --continue testDebugUnitTest :core:model:test :app:testApkDebugUnitTest :app:testPlayDebugUnitTest
  tests_status=$?
  counts=$($PY - <<'EOF'
import glob, xml.etree.ElementTree as ET
dirs = glob.glob('*/*/build/test-results/testDebugUnitTest') + ['core/model/build/test-results/test',
       'app/build/test-results/testApkDebugUnitTest', 'app/build/test-results/testPlayDebugUnitTest']
t = f = s = 0
for d in dirs:
    for x in glob.glob(d + '/TEST-*.xml'):
        r = ET.parse(x).getroot()
        t += int(r.get('tests', 0)); f += int(r.get('failures', 0)) + int(r.get('errors', 0)); s += int(r.get('skipped', 0))
print(f'{t} tests, {f} failed, {s} skipped in {len([d for d in dirs if glob.glob(d + "/TEST-*.xml")])} test tasks')
EOF
)
  if [ $tests_status = 0 ]; then
    record PASS "gradle: unit tests (every module)" "$counts, $(( $(date +%s) - start )) s"
  else
    record FAIL "gradle: unit tests (every module)" "$counts; $(grep -E '^> Task .*FAILED|tests completed' "$LOGS/gradle-tests.log" | head -2 | tr '\n' ' ' | cut -c1-160) (log: $LOGS/gradle-tests.log)"
  fi
  start=$(date +%s)
  gradle "$LOGS/gradle-lint.log" :app:lintApkRelease :app:lintPlayRelease
  lint_status=$?
  lint_counts=$($PY - <<'EOF'
import collections, os, xml.etree.ElementTree as ET
c = collections.Counter()
for v in ('apkRelease', 'playRelease'):
    p = f'app/build/reports/lint-results-{v}.xml'
    if os.path.exists(p):
        for i in ET.parse(p).getroot().iter('issue'):
            c[(v, i.get('severity'))] += 1
print(', '.join(f"{v}: {c[(v, 'Fatal')] + c[(v, 'Error')]} errors, {c[(v, 'Warning')]} warnings"
                for v in ('apkRelease', 'playRelease')))
EOF
)
  if [ $lint_status = 0 ]; then
    record PASS "gradle: lint apkRelease + playRelease" "$lint_counts (app/build/reports/lint-results-*.html)"
  else
    record FAIL "gradle: lint apkRelease + playRelease" "$lint_counts; $(gradle_why "$LOGS/gradle-lint.log") (log: $LOGS/gradle-lint.log)"
  fi
else
  record SKIP "gradle: build apkRelease + playRelease" "--checks-only: checking the outputs already in app/build"
  record SKIP "gradle: unit tests (every module)" "--checks-only"
  record SKIP "gradle: lint apkRelease + playRelease" "--checks-only"
fi

# 7. Artifact checks -------------------------------------------------------------------------------------------------
missing=""
for f in "$APK_A" "$APK_P" "$AAB"; do [ -f "$f" ] || missing="$missing $f"; done
if [ -n "$missing" ] || [ $BUILD_OK = 0 ]; then
  record FAIL "artifacts" "not built:${missing:- (build failed)}; artifact checks skipped"
else
  BASE=$OUT/work/app-play-release.aab-base.apk
  check "artifact: bundle base module readable" "$LOGS/aab-base.log" $PY tools/release/artifact_checks.py aab-base "$AAB" "$BASE"
  ARTS="$APK_A $APK_P $BASE"

  check "artifact: version, SDK levels, ABIs, flags" "$LOGS/badging.log" \
    $PY tools/release/artifact_checks.py badging "$AAPT2" "$VERSION_NAME" "$VERSION_CODE" $ARTS

  # Signatures. apksigner for the APKs, jarsigner + keytool for the bundle.
  sig_problems=""
  sig_detail=""
  fp_note=""
  : > "$LOGS/signatures.log"
  for apk in "$APK_A" "$APK_P"; do
    out=$("$APKSIGNER" verify --print-certs -v "$apk" 2>&1)
    printf '== %s\n%s\n' "$apk" "$out" >> "$LOGS/signatures.log"
    cert=$(printf '%s\n' "$out" | sed -nE 's/^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$/\1/p')
    dn=$(printf '%s\n' "$out" | sed -nE 's/^Signer #1 certificate DN: (.*)$/\1/p')
    signers=$(printf '%s\n' "$out" | sed -nE 's/^Number of signers: ([0-9]+)$/\1/p')
    schemes=$(printf '%s\n' "$out" | sed -nE 's/^Verified using (v[0-9.]+) scheme.*: true$/\1/p' | tr '\n' '+' | sed 's/+$//')
    printf '%s\n' "$out" | grep -qx 'Verifies' || sig_problems="$sig_problems $(basename "$apk") does not verify;"
    printf '%s\n' "$out" | grep -q 'v2 scheme (APK Signature Scheme v2): true' || sig_problems="$sig_problems $(basename "$apk") has no v2 signature;"
    [ "$signers" = 1 ] || sig_problems="$sig_problems $(basename "$apk") has ${signers:-no} signers;"
    case "$apk" in *apk-release*) CERT_APK=$cert ;; *) CERT_PLAY=$cert ;; esac
    sig_detail="$sig_detail $(basename "$apk" .apk) ${cert:0:16}... ($schemes, $dn);"
  done
  jar=$(jarsigner -verify "$AAB" 2>&1)
  printf '== %s\n%s\n' "$AAB" "$jar" >> "$LOGS/signatures.log"
  printf '%s\n' "$jar" | grep -q '^jar verified' || sig_problems="$sig_problems the bundle does not verify;"
  CERT_AAB=$(keytool -printcert -jarfile "$AAB" 2>/dev/null | sed -nE 's/^[[:space:]]*SHA256: ([0-9A-F:]+)$/\1/p' | head -1 | tr -d ':' | tr 'A-F' 'a-f')
  sig_detail="$sig_detail aab ${CERT_AAB:0:16}... (JAR signature)"
  [ "$CERT_PLAY" = "$CERT_AAB" ] || sig_problems="$sig_problems the play APK and bundle certificates differ;"
  if [ "$UPLOAD_KEYS" != 4 ] && [ "$CERT_APK" != "$CERT_PLAY" ]; then
    sig_problems="$sig_problems apk and play certificates differ without an upload key;"
  fi
  if [ $SIGNING = debug ]; then
    debug_signed=$(grep -c '^Signer #1 certificate DN: C=US, O=Android, CN=Android Debug$' "$LOGS/signatures.log")
    [ "$debug_signed" = 2 ] || sig_problems="$sig_problems no release key is configured, but the APKs are not debug-signed (outputs of an earlier build with other keys? rebuild without --checks-only);"
  fi
  if [ $SIGNING = release ]; then
    grep -q 'CN=Android Debug' "$LOGS/signatures.log" && sig_problems="$sig_problems a release key is configured but an artifact is debug-signed;"
    if [ -n "${SIGNER_RELEASE_CERT_SHA256:-}" ]; then
      want=$(printf '%s' "$SIGNER_RELEASE_CERT_SHA256" | tr -d ': ' | tr 'A-F' 'a-f')
      [ "$want" = "$CERT_APK" ] || sig_problems="$sig_problems apk certificate is not SIGNER_RELEASE_CERT_SHA256;"
      fp_note="= SIGNER_RELEASE_CERT_SHA256"
    else
      fp_note="not compared: SIGNER_RELEASE_CERT_SHA256 unset, acceptable for the first release only"
    fi
  fi
  if [ -n "$sig_problems" ]; then
    record FAIL "artifact: signatures" "$sig_problems (log: $LOGS/signatures.log)"
  elif [ $SIGNING = debug ]; then
    record WARN "artifact: signatures" "verified, but DEBUG-SIGNED:$sig_detail"
  else
    record PASS "artifact: signatures" "apk certificate SHA-256 $CERT_APK ($fp_note);$sig_detail"
  fi

  check "artifact: offline (check-offline.sh)" "$LOGS/offline.log" tools/release/check-offline.sh $ARTS

  if $PY tools/release/check-16kb.py "$APK_A" "$APK_P" "$AAB" > "$LOGS/16kb.log" 2>&1 \
     && "$ZIPALIGN" -c -P 16 4 "$APK_A" >> "$LOGS/16kb.log" 2>&1 \
     && "$ZIPALIGN" -c -P 16 4 "$APK_P" >> "$LOGS/16kb.log" 2>&1; then
    record PASS "artifact: 16 KB alignment of every .so" "$(grep 'native libraries checked' "$LOGS/16kb.log") (APKs + AAB); zipalign -c -P 16 OK for both APKs"
  else
    record FAIL "artifact: 16 KB alignment of every .so" "$(grep -E 'BAD|FAIL' "$LOGS/16kb.log" | head -1 | cut -c1-160) (log: $LOGS/16kb.log)"
  fi

  check "artifact: engine digest = pin = iOS" "$LOGS/engine.log" \
    $PY tools/release/artifact_checks.py engine "$PIN_SHA" "$PIN_BYTES" "${IOS:--}" "$APK_A" "$APK_P" "$AAB"
  [ -z "$IOS" ] && record WARN "artifact: engine and data vs iOS" "--ios none: engine compared with the Android pin only, data files not compared"

  check "artifact: data = iOS, legal = flavor texts" "$LOGS/data.log" \
    $PY tools/release/artifact_checks.py data "$ROOT" "${IOS:--}" "apk=$APK_A" "play=$APK_P" "play=$AAB"

  # Permissions: aapt2 on every artifact and the Gradle merged-manifest reports.
  want=$(printf '%s\n' "$EXPECTED_PERMISSIONS" | sort -u)
  perm_problems=""
  : > "$LOGS/permissions.log"
  for a in $ARTS; do
    got=$("$AAPT2" dump permissions "$a" 2>&1 | grep -E '^uses-permission' | grep -oE "name='[^']+'" | sed "s/name='//; s/'//" | sort -u)
    printf '== %s\n%s\n' "$a" "$got" >> "$LOGS/permissions.log"
    [ "$got" = "$want" ] || perm_problems="$perm_problems $(basename "$a"): +[$(comm -13 <(echo "$want") <(echo "$got") | tr '\n' ' ')] -[$(comm -23 <(echo "$want") <(echo "$got") | tr '\n' ' ')];"
  done
  for v in apkRelease playRelease; do
    r=app/build/reports/permissions/$v.txt
    if [ ! -f "$r" ]; then perm_problems="$perm_problems no Gradle report $r;"
    elif [ "$(sort -u "$r")" != "$want" ]; then perm_problems="$perm_problems Gradle report $r differs;"; fi
  done
  if [ -z "$perm_problems" ]; then
    record PASS "artifact: permissions = G8 allowlist" "$(echo "$want" | sed 's/android.permission.//; s/com.bittool.signer.//' | tr '\n' ' ')in all 3 + Gradle reports"
  else
    record FAIL "artifact: permissions = G8 allowlist" "$perm_problems (log: $LOGS/permissions.log)"
  fi

  # Components, startup initializers and the debug harness: enforced by the build (check*Components,
  # check*HasNoHarness); their reports must exist and be clean.
  comp_problems=""
  for v in apkRelease playRelease; do
    c=app/build/reports/components/$v.txt
    h=app/build/reports/release/$v-harness.txt
    [ -f "$c" ] || comp_problems="$comp_problems no $c;"
    [ -f "$h" ] || comp_problems="$comp_problems no $h;"
    [ -f "$c" ] && { grep -q '^queries elements: 0$' "$c" && grep -q '^Play services / Firebase / Play Store modules: \[\]$' "$c" \
      && grep -q '^exported: \[activity:com.bittool.signer.MainActivity\]$' "$c" || comp_problems="$comp_problems $c not clean;"; }
    [ -f "$h" ] && { [ "$(grep -c ': \[\]$' "$h")" = 2 ] || comp_problems="$comp_problems $h not clean;"; }
  done
  if [ -z "$comp_problems" ]; then
    record PASS "artifact: components, no debug harness" "only MainActivity exported, no <queries>, no Play services; R8 mapping and dex free of the UI-test harness"
  else
    record FAIL "artifact: components, no debug harness" "$comp_problems"
  fi

  check "artifact: assets listing" "$LOGS/assets.log" $PY tools/release/artifact_checks.py assets "$OUT/assets" "$APK_A" "$APK_P" "$AAB"

  # Third-party notices (backlog B19): the release runtime classpath, listed for the manual review and matched against
  # THIRD_PARTY_LICENSES_ANDROID.txt.
  notices_ok=1
  : > "$LOGS/notices.log"
  for v in apkRelease playRelease; do
    $PY tools/release/artifact_checks.py notices "app/build/outputs/sdk-dependencies/$v/sdkDependencies.txt" \
      core/data/src/main/assets/legal/THIRD_PARTY_LICENSES_ANDROID.txt "$OUT/dependencies-$v.txt" >> "$LOGS/notices.log" 2>&1 || notices_ok=0
  done
  if [ $notices_ok = 1 ]; then
    record PASS "artifact: third-party notices" "$(tail -1 "$LOGS/notices.log") (list: dependencies-*.txt)"
  else
    record FAIL "artifact: third-party notices" "$(grep -E 'BAD|rror' "$LOGS/notices.log" | head -1 | cut -c1-180) (log: $LOGS/notices.log)"
  fi

  sa=$(size_of "$APK_A"); sp=$(size_of "$APK_P"); sb=$(size_of "$AAB")
  size_detail="apk $(mb "$sa"), play APK $(mb "$sp"), AAB $(mb "$sb") (budgets $(mb $MAX_APK_BYTES) / $(mb $MAX_AAB_BYTES))"
  if [ "$sa" -le "$MAX_APK_BYTES" ] && [ "$sp" -le "$MAX_APK_BYTES" ] && [ "$sb" -le "$MAX_AAB_BYTES" ]; then
    record PASS "artifact: sizes" "$size_detail"
  else
    record FAIL "artifact: sizes" "over budget: $size_detail"
  fi

  # 8. Archive ------------------------------------------------------------------------------------------------------
  arch_problems=""
  suffix=""
  [ $SIGNING = debug ] && suffix="-DEBUG-SIGNED"
  cp "$APK_A" "$OUT/BitToolSigner-$VERSION_NAME-$VERSION_CODE-apk$suffix.apk"
  cp "$AAB" "$OUT/BitToolSigner-$VERSION_NAME-$VERSION_CODE-play$suffix.aab"
  for v in apkRelease playRelease; do
    m=app/build/outputs/mapping/$v/mapping.txt
    if [ -s "$m" ] && grep -q '^com\.bittool\.signer\.' "$m"; then
      mkdir -p "$OUT/mapping/$v" && cp "$m" "$OUT/mapping/$v/mapping.txt"
    else
      arch_problems="$arch_problems no R8 mapping $m;"
    fi
    s=app/build/outputs/native-debug-symbols/$v/native-debug-symbols.zip
    if [ -s "$s" ]; then
      mkdir -p "$OUT/native-debug-symbols" && cp "$s" "$OUT/native-debug-symbols/$v.zip"
    else
      arch_problems="$arch_problems no native debug symbols $s;"
    fi
  done
  # What Play receives inside the bundle (docs/release.md §7): the R8 mapping, the same file as the archived one, and
  # the native symbol tables, so Play Console can deobfuscate and symbolicate without a manual upload.
  aab_entries=$(unzip -Z1 "$AAB" 2>/dev/null)
  if ! printf '%s\n' "$aab_entries" | grep -qx 'BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map'; then
    arch_problems="$arch_problems the AAB carries no R8 mapping (BUNDLE-METADATA/…obfuscation/proguard.map);"
  elif ! unzip -p "$AAB" BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map \
      | cmp -s - app/build/outputs/mapping/playRelease/mapping.txt; then
    arch_problems="$arch_problems the AAB's R8 mapping is not app/build/outputs/mapping/playRelease/mapping.txt;"
  fi
  printf '%s\n' "$aab_entries" | grep -qE '^BUNDLE-METADATA/com\.android\.tools\.build\.debugsymbols/[^/]+/[^/]+\.so\.(sym|dbg)$' \
    || arch_problems="$arch_problems the AAB carries no native debug symbols (BUNDLE-METADATA/…debugsymbols);"
  if [ $IN_GIT = 1 ]; then git ls-files -s > "$OUT/tracked-files.txt" 2>/dev/null
  else echo "not a git checkout: no tracked files" > "$OUT/tracked-files.txt"; fi
  if [ $SIGNING = debug ]; then
    printf '%s\n' "DEBUG-SIGNED BUILD: NOT FOR DISTRIBUTION." \
      "Built by tools/release/verify-release.sh without a release key (-Psigner.allowDebugSigning=true)." \
      "Install it only on test devices. A distributed build is signed with the release key (docs/release.md)." \
      > "$OUT/NOT-FOR-DISTRIBUTION.txt"
  fi
  (cd "$OUT" && shasum -a 256 *.apk *.aab mapping/*/mapping.txt native-debug-symbols/*.zip > SHA256SUMS 2>/dev/null)
  ENGINE_APK=$(unzip -p "$APK_A" assets/engine/signer.js | shasum -a 256 | cut -d' ' -f1)
  COMMIT="$COMMIT" VERSION_NAME="$VERSION_NAME" VERSION_CODE="$VERSION_CODE" SIGNING="$SIGNING" \
  SIGNING_LABEL="$SIGNING_LABEL" CERT_APK="${CERT_APK:-}" CERT_PLAY="${CERT_PLAY:-}" ENGINE_APK="$ENGINE_APK" \
  TREE_CLEAN="$([ -z "$dirty" ] && echo true || echo false)" CHECKS_ONLY="$([ $GRADLE_STEPS = 0 ] && echo true || echo false)" \
  OUT="$OUT" $PY - <<'EOF' || arch_problems="$arch_problems release-manifest.json not written;"
import datetime, hashlib, json, os
out = os.environ['OUT']
files = {}
for line in open(os.path.join(out, 'SHA256SUMS')):
    digest, name = line.split(None, 1)
    name = name.strip()
    files[name] = {'sha256': digest, 'bytes': os.path.getsize(os.path.join(out, name))}
manifest = {
    'app': 'BitTool Signer for Android',
    'applicationId': 'com.bittool.signer',
    'versionName': os.environ['VERSION_NAME'],
    'versionCode': int(os.environ['VERSION_CODE']),
    'commit': os.environ['COMMIT'],
    'treeClean': os.environ['TREE_CLEAN'] == 'true',
    # --checks-only: the artifacts were not built by this run, so the commit above need not be the one they came from.
    'checksOnly': os.environ['CHECKS_ONLY'] == 'true',
    'builtAt': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
    'signing': {'mode': os.environ['SIGNING'], 'label': os.environ['SIGNING_LABEL'],
                'apkCertificateSha256': os.environ['CERT_APK'], 'playCertificateSha256': os.environ['CERT_PLAY']},
    'engineSha256': os.environ['ENGINE_APK'],
    'artifacts': files,
}
with open(os.path.join(out, 'release-manifest.json'), 'w') as f:
    json.dump(manifest, f, indent=2)
    f.write('\n')
EOF
  if [ -z "$arch_problems" ]; then
    record PASS "archive: APK, AAB, mappings, symbols" "$OUT"
  else
    record FAIL "archive: APK, AAB, mappings, symbols" "$arch_problems"
  fi
fi

# ---------------------------------------------------------------------------------------------------------- summary
{
  echo
  echo "BitTool Signer $VERSION_NAME ($VERSION_CODE) - commit $COMMIT"
  echo "Signing: $SIGNING_LABEL"
  [ $STRICT = 1 ] && echo "Mode: --release (warnings and skips fail)"
  echo
  cat "$OUT/work/results"
  echo
  if [ $FAILS = 0 ]; then
    echo "RESULT: PASS - $N checks, $WARNS warning(s), $SKIPS skipped$([ $SIGNING = debug ] && echo ' - DEBUG-SIGNED, NOT FOR DISTRIBUTION')"
  else
    echo "RESULT: FAIL - $FAILS of $N checks failed, $WARNS warning(s), $SKIPS skipped"
  fi
  echo "Artifacts and logs: $OUT"
  echo "Still manual (docs/release.md): legal review, screenshot tour, third-party wallet round trips, App Lock"
  echo "biometrics and the lost-key path on a real phone, StrongBox, Play Console / signing-key steps."
} | tee "$OUT/summary.txt"
rm -rf "$OUT/work"
[ $FAILS = 0 ]
