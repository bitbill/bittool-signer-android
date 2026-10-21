# BitTool Signer for Android: release checklist

This is the checklist for shipping both distributions of BitTool Signer for Android:

- `apkRelease`, the direct download from bittool.com/signer (a universal APK);
- `playRelease`, the Google Play App Bundle.

Both are offline and functionally identical (DECISIONS §1, §11). They have the same applicationId `com.bittool.signer`, the same versionCode, the same engine byte for byte, and the same permission set. They differ only in their legal texts, their About / Open Source content, their signing and their packaging (a universal APK, or an App Bundle from which Play builds split APKs).

The checklist has two halves:

1. **Automated.** One command builds both flavors and runs every check a tool can make: `tools/release/verify-release.sh` (§5). It has to pass in `--release` mode on the commit you tag.
2. **Manual.** Decisions and acceptance items that no tool can do (§1, §8–§11). They have to be done and recorded (§13) for the same build.

A green `verify-release.sh` is necessary but not sufficient.

Sources: `docs/port/ownbit-build-release.md` §3, §5, §7 and §11, `docs/port/gaps.md` G1–G3 and G8, `docs/port/DECISIONS.md`, `docs/qa/backlog.md`, and the iOS `release/Device-Self-Test.md` and `IMPLEMENTATION_STATUS.md` ("Still requiring external acceptance").

---

## 1. Decision for the owner: the app signing key (before the first Play upload)

**Status: open. The owner must decide this before the first upload to Google Play. It cannot be undone afterwards; only Play's key upgrade or rotation can change it later.**

The question is which key signs the app that Google Play installs. Play App Signing is mandatory for App Bundles, so Google signs what Play delivers. The choice is only about *which* key Google holds.

| Option | What happens | Consequence for BitTool Signer |
|---|---|---|
| **A. One app signing key for both channels (recommended)** | In Play Console › App integrity › App signing, choose "Export and upload a key from Java keystore". Upload the same key that signs the `apk` releases, using Google's PEPK tool. Then sign every upload with a separate **upload key** (`signer.upload.*`, §4). | Both channels have one certificate, and one SHA-256 fingerprint to publish and to show in the app. An `apk` install and a Play install of the same versionCode or higher update each other in place, so switching channels keeps every wallet. Google then holds a copy of the release key. |
| B. Google-generated app signing key (what happened to Ownbit) | Play creates its own key. The `apk` channel keeps the release key. | There are two certificates and two published fingerprints. The `apk` and Play installs **cannot** update each other. Switching channels means uninstalling, and **uninstalling deletes every wallet's encrypted vault and its Keystore keys**: only the recovery phrase brings a wallet back. It is acceptable only with a separate applicationId for one channel (a build change that does not exist today) and a loud warning on the website and in the app. |

Recommendation: **A**. The build already implements it. When all four `signer.upload.*` values are set, only `playRelease` is signed with the upload key; `apkRelease` keeps the release key (`app/build.gradle.kts`, `androidComponents.onVariants … withFlavor("distribution" to "play")`).

Steps under A, done once by the key owner and never on a shared machine:

1. Generate the release key. Use RSA 4096. Play requires a validity ending after 22 October 2033; take 30 years or more. Keep two offline backups, in separate places. If the key is lost, the `apk` channel can never ship another update that installs over the existing one.
2. Generate a separate upload key.
3. Enroll Play App Signing with the release key (PEPK), then register the upload key's certificate.
4. Check that Play Console › App integrity › App signing key certificate › SHA-256 equals the `apk` certificate SHA-256 that `verify-release.sh` prints (§4). That value is the published fingerprint.

Record the decision here before the first upload:

```
Signing-key decision: [ ] A  [ ] B      Owner: ____________      Date: __________
Published certificate SHA-256 (apk; play under A): ________________________________________________
```

---

## 2. Prerequisites (release machine)

- macOS with the Android SDK at `$ANDROID_HOME` (default `~/Library/Android/sdk`): build-tools 36 (`aapt2`, `apksigner`, `zipalign`), NDK 28.2, CMake 3.22.1.
- JDK 21. The default is the JetBrains Runtime that `tools/gradle-slot.sh` uses; `keytool` and `jarsigner` come from it.
- `python3` 3.9 or newer.
- The iOS repository at `~/Documents/bitsigner-ios/BitSigner`, or set `SIGNER_IOS_REPO=<path>` or pass `--ios <path>`. It is read only. The script compares the engine, the data files, the translation catalogue and the version with it.
- A clean clone or worktree at the commit to release. Every Gradle build goes through `tools/gradle-slot.sh` (the script does this).
- The signing keys (§4), on the release machine only.

---

## 3. Version and build number

- **versionName** is `signerVersionName` in `app/build.gradle.kts`. It stays in step with the iOS `MARKETING_VERSION`; `verify-release.sh` compares the two. A bump is a one-line commit.
- **versionCode** is `MAJOR·1 000 000 + MINOR·10 000 + PATCH·100 + BUILD`, with MINOR, PATCH and BUILD each in 0–99. For example, 1.0.0 (1) gives **1000001**. Both flavors use the same versionCode.
- **BUILD** defaults to 1, the iOS `CURRENT_PROJECT_VERSION`, so Settings shows "1.0.0 (1)" like iOS (DECISIONS §10). To re-issue the same versionName, for example after Play rejected an upload, pass `--build-number N` (the script forwards `-Psigner.buildNumber=N`).
  - Play never accepts a versionCode twice, even for an upload that was never released.
  - Under option A, an `apk` user moving to Play, or the reverse, needs a versionCode that does not decrease.
- The script checks the versionName, versionCode, `minSdk 26`, `targetSdk 36` and ABIs (`arm64-v8a armeabi-v7a x86_64`) in all three artifacts, and checks that the artifacts agree.

---

## 4. Signing

**Where the keys come from.** They come from `local.properties` (git-ignored, never committed) or from the environment. Never print the values, and never put them in a file under the repository.

```
signer.release.storeFile=/absolute/path/release.jks        # or relative to the repo root  (env SIGNER_RELEASE_STOREFILE)
signer.release.storePassword=…                              # SIGNER_RELEASE_STOREPASSWORD
signer.release.keyAlias=…                                   # SIGNER_RELEASE_KEYALIAS
signer.release.keyPassword=…                                # SIGNER_RELEASE_KEYPASSWORD
signer.upload.storeFile=… (+ storePassword, keyAlias, keyPassword)   # optional: the Play upload key (option A)
```

`*.jks` and `*.keystore` are git-ignored.

**Signing modes.** The Gradle task `pre{Apk,Play}ReleaseBuild` prints one of these modes:

- "signed with the release key";
- "play signed with the Play upload key";
- "signed with the DEBUG key";
- "UNSIGNED".

A partially configured key fails the build and names the missing values.

**What `verify-release.sh` does with them:**

- **Release key configured:** both flavors are signed with it, and `play` with the upload key when one is configured. Set `SIGNER_RELEASE_CERT_SHA256=<published fingerprint>` (either format, see below) to have the script compare the `apk` certificate with it. Do this for every release after the first. Without it the signatures line says "not compared"; that is acceptable only for the first release, which defines the fingerprint.
- **No release key:** the script adds `-Psigner.allowDebugSigning=true`, so the checks can still run on any machine. Everything is then labelled **DEBUG-SIGNED — NOT FOR DISTRIBUTION**:
  - the output directory and file names end in `-DEBUG-SIGNED`;
  - a `NOT-FOR-DISTRIBUTION.txt` file is added;
  - the summary line and the signatures line say so.

  `--release` refuses this mode.
- **Signature checks:**
  - `apksigner verify` must pass on both APKs, with exactly one signer and a v2 signature.
  - `jarsigner -verify` must pass on the AAB.
  - Without an upload key, the `apk`, `play` and AAB certificates must be equal. With one, the play APK and the AAB must be equal.

**Signature schemes.** The APKs carry **v2 only**. That is the AGP default at minSdk 26: no v1 is needed, and AGP does not add v3 unless asked. ownbit-build-release §7.2.5 expected "v2 + v3", but v3 is not required to install or to update. A future key rotation signs *that* release with a v3.1 lineage (`apksigner rotate`, then `apksigner sign --lineage`), whatever the current release carries. To add v3 now, the owner of `app/**` sets `enableV3Signing = true` on the release signing config.

**Fingerprint formats.** `apksigner verify --print-certs` prints the certificate SHA-256 as 64 lowercase hex digits. That is the form the `apk` Terms of Use tell users to compare. The app's Settings › Fully Open Source › This build › Signing certificate shows the same digest as uppercase, colon-separated hex, the `keytool` and Play Console form (`SigningCertificate.kt`). Publish both forms on bittool.com/signer.

This is an open point for the owner of `app/**` and the legal review (§8). The `apk` Terms say "the same value appears in Settings › Fully Open Source › This build", but the app shows that digest in the other format. ownbit-build-release §11 recommended the `apksigner` form (lowercase, no separators), so users can compare character by character. Either the app shows that form, or the Terms name both forms. Until then, the website must show both forms side by side.

---

## 5. The automated verification: `tools/release/verify-release.sh`

```bash
tools/release/verify-release.sh                    # any machine; debug-signed (labelled) when no release key is configured
tools/release/verify-release.sh --release          # the release candidate: release key, clean tree, iOS repo; warnings fail
tools/release/verify-release.sh --checks-only      # re-check the artifacts already in app/build (no Gradle)
tools/release/verify-release.sh --build-number 2   # re-issue 1.0.0 as 1000002
tools/release/verify-release.sh --ios none         # no iOS repository: compare with the Android pins only (warns)
```

An iOS path without `engine/bundle-manifest.json` (a wrong `--ios`, `SIGNER_IOS_REPO`, or a machine without the default checkout) stops the script at once with exit status 2. It does not turn into a list of failed comparisons.

**Run time.** A cold run takes 5–15 minutes: the release build is about 5 minutes, lint about 2, plus the unit tests. A run with a warm Gradle build cache takes under a minute.

**Output.** One line per check, then the summary. The exit status is 0 when no check failed; outside `--release`, warnings are allowed.

- **PASS / FAIL** are results.
- **WARN** is a condition that is acceptable for a dry run but not for a release, for example debug signing, a dirty tree, no iOS repository or a different iOS version. `--release` turns every WARN into FAIL.
- **SKIP** marks a step `--checks-only` left out. `--release` fails it too.
- **INFO** facts never fail: the tag is not yet set, no upload key.

**Archive.** Everything is written to `build/release/BitToolSigner-<versionName>-<versionCode>[-DEBUG-SIGNED]/`. A `--checks-only` run writes to the same name with `-checks-only` appended, so it never replaces the archive of a full run, and its `release-manifest.json` says `"checksOnly": true`: it did not build the artifacts, so the recorded commit need not be theirs.

| File | Contents |
|---|---|
| `BitToolSigner-…-apk.apk`, `BitToolSigner-…-play.aab` | the two distributables |
| `mapping/{apkRelease,playRelease}/mapping.txt`, `native-debug-symbols/{apkRelease,playRelease}.zip` | R8 mappings and native symbol tables (§7) |
| `SHA256SUMS` | `shasum -a 256` of the APK, the AAB, both mappings and both symbol archives |
| `release-manifest.json` | versionName and versionCode, commit, clean-tree flag, `checksOnly` flag, build time, signing mode, certificate SHA-256 of `apk` and `play`, engine SHA-256 read from the APK, artifact hashes and sizes |
| `tracked-files.txt` | `git ls-files -s`: the blob hash of every tracked file at the build |
| `dependencies-{apkRelease,playRelease}.txt` | the release runtime classpath, `group:artifact:version` (input for the notices review, §8) |
| `assets/*.assets.txt` | the asset listing of each artifact (size, stored or deflated) |
| `logs/` | the output of every step (Gradle, apksigner, check-offline, 16 KB, …) |
| `summary.txt` | the summary as printed |

**What each line checks:**

| Line | Proves | How |
|---|---|---|
| `git: clean tree`, `git: tag android-v<version>` | The build is reproducible from a commit. A directory that is not a git checkout (an export, a copy) warns, so `--release` fails there. The tag line is information only; tag the commit that passed `--release`. | `git status --porcelain`, `git rev-parse` |
| `version` | versionName = iOS `MARKETING_VERSION` | iOS `project.pbxproj` |
| `signing: release key`, `signing: Play upload key` | Which keys are configured (values are never read out) | `local.properties` or the environment, as the build reads them |
| `l10n: iOS catalogue import up to date` | `strings_ios.xml` × 21 and `IosStrings.kt` are what the importer generates from the catalogue copies | `tools/l10n/import_ios_strings.py --check` |
| `l10n: Android-only strings translated` | Every Android-only string exists in all 20 other languages and no translation is stale | `tools/l10n/android_strings.py check` |
| `l10n: importer tests` | The importer and override rules still hold | `python3 -m unittest discover -s tools/l10n` |
| `l10n: catalogue copies = iOS` | `tools/l10n/translations/*.json` and `interface-strings.json` are byte-identical with the iOS repository | `cmp` |
| `gradle: build apkRelease + playRelease` | R8 full mode and resource shrinking succeed for `:app:assembleApkRelease :app:assemblePlayRelease :app:bundlePlayRelease`. The build itself fails on a merged manifest outside the permission allowlist (`check*Permissions`), on an unexpected component, exported component, `<queries>`, startup initializer or Play services / Firebase / Play Core module (`check*Components`), and on the debug UI-test harness or test-only key stores in the R8 mapping or dex (`check*HasNoHarness`). | Gradle |
| `gradle: unit tests (every module)` | `testDebugUnitTest` of all 16 Android modules, `:core:model:test`, and `:app:test{Apk,Play}DebugUnitTest`. These include `EngineDigestTest` (the engine pin), `LegalTextsTest` (both flavors' legal texts) and the Roborazzi screenshot recordings. | Gradle `--continue`; counts read from the JUnit XML |
| `gradle: lint apkRelease + playRelease` | No lint error in any module (`checkDependencies = true`; `VisibleForTests` is an error). Warnings are counted only. | `:app:lintApkRelease :app:lintPlayRelease`; reports in `app/build/reports/lint-results-*.html` |
| `artifact: bundle base module readable` | The AAB's base module, repackaged in APK layout, so `aapt2` and `check-offline.sh` can read what Play will deliver without bundletool | `artifact_checks.py aab-base` |
| `artifact: version, SDK levels, ABIs, flags` | §3, for all three; and the release manifest flags: not debuggable, not `testOnly`, `allowBackup="false"` (DECISIONS §6) | `aapt2 dump badging`, `aapt2 dump xmltree` |
| `artifact: signatures` | §4 | `apksigner`, `jarsigner`, `keytool` |
| `artifact: offline (check-offline.sh)` | No network permission, no networking, WebView, DownloadManager or PackageInstaller class reference in the dex, no Play services / Firebase / Play Core, and no socket symbol in any native library (DECISIONS §11) | `tools/release/check-offline.sh` on both APKs and on the AAB base |
| `artifact: 16 KB alignment of every .so` | Every PT_LOAD segment of every `.so` (all ABIs, 32-bit included) is aligned to 16 KB or more, every `.so` in the APKs is stored and starts on a 16 KB ZIP boundary, and `zipalign -c -P 16 4` passes. This is the Android 15+ and Google Play requirement. | `tools/release/check-16kb.py`, `zipalign` |
| `artifact: engine digest = pin = iOS` | `assets/engine/signer.js` of all three = `EngineBundle.EXPECTED_SHA256` / `EXPECTED_BYTES` = iOS `engine/bundle-manifest.json` = iOS `BitToolSigner/Resources/signer.js`, and it is stored uncompressed in the APKs (§6) | `artifact_checks.py engine` |
| `artifact: data = iOS, legal = flavor texts` | The token registry, known contracts, token icon pack and index, the ten BIP39 lists and the engine's `THIRD_PARTY_LICENSES.txt` are byte-identical with the iOS resources. Each artifact's Terms of Use and Privacy Policy are its flavor's own file (`app/src/{apk,play}/assets/legal/`). | `artifact_checks.py data` |
| `artifact: permissions = G8 allowlist` | Exactly `CAMERA`, `USE_BIOMETRIC`, `USE_FINGERPRINT`, `DETECT_SCREEN_CAPTURE`, `DETECT_SCREEN_RECORDING`, `HIDE_OVERLAY_WINDOWS` and `com.bittool.signer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, in all three artifacts and in the Gradle reports (`app/build/reports/permissions/`) | `aapt2 dump permissions` |
| `artifact: components, no debug harness` | The Gradle component and harness reports are clean: only `MainActivity` is exported, there are no `<queries>` and no Play services, and the mapping and dex are free of the harness | `app/build/reports/{components,release}/` |
| `artifact: assets listing` | The assets are exactly the signer's own 19 (gaps.md G8), plus ART's `dexopt/baseline.prof{,m}` in the APKs: no library test assets, nothing fetched | `artifact_checks.py assets` |
| `artifact: third-party notices` | Every non-AndroidX runtime library is named by a component entry of `THIRD_PARTY_LICENSES_ANDROID.txt` (a block ending "(full text below)"). It counts when the entry gives its artifactId or a non-generic word of its coordinates together with its version, as "Dagger and Hilt 2.60.1" covers `hilt-android:2.60.1`, or a distinctive artifactId alone, such as Guava's `failureaccess`. A version that appears only somewhere else, for example "Apache-2.0" for a library at 2.0, does not count, and neither does a generic artifactId like `core`. A version bump of a library that is already named is not caught: that is the B19 refresh (§8). AndroidX and Compose fall under the blanket sections and are reviewed by hand. | `artifact_checks.py notices` on AGP's `sdkDependencies.txt` |
| `artifact: sizes` | APK ≤ 26 MB and AAB ≤ 30 MB (1.0.0: 20.7 MB and 24.2 MB; `TokenIcons.pack` alone is 11.2 MB). The budgets catch an accidental dependency or asset. Raise them deliberately in the script (`SIGNER_MAX_APK_BYTES` / `SIGNER_MAX_AAB_BYTES` override them for a single run). | `wc -c` |
| `archive: APK, AAB, mappings, symbols` | The archive is complete (above), with both R8 mappings and both native symbol archives. The AAB carries its R8 mapping (`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`), byte-identical with the archived `mapping/playRelease/mapping.txt`, and the native symbol tables (`BUNDLE-METADATA/com.android.tools.build.debugsymbols/<abi>/*.so.sym`) that Play Console reads (§7) | `unzip`, `cmp` |

**Fixing a failure.**

- Fix the cause in the code that owns it. Never weaken a check to get a release out.
- A failure caused by stale outputs, such as `--checks-only` after a build with other keys, goes away with a full run.
- The allowlists live in two places that must agree: `app/build.gradle.kts` (the build-time checks) and `tools/release/verify-release.sh` / `artifact_checks.py` (the artifact checks). Change a permission or an asset in both, with the reason, in the same commit.

---

## 6. Engine byte-identity with iOS

There is one engine (DECISIONS §11). `core/engine/src/main/assets/engine/signer.js` is the iOS file, byte for byte, and its SHA-256 is pinned in `EngineBundle.EXPECTED_SHA256` / `EXPECTED_BYTES`. The engine checks the pin at runtime before it compiles the file, `EngineDigestTest` checks it at build time, and `verify-release.sh` checks it in every artifact. For 1.0.0 the digest is `6933d0d8a6f60faa8222506ac0bd7eef80eb884c9f73e31e9f354b4740f21077` (1,181,026 bytes).

When iOS ships a new engine:

1. In the iOS repository, the engine is rebuilt reproducibly and `engine/bundle-manifest.json` is updated (iOS `docs/Reproducible-Build.md`).
2. Run `tools/engine/sync-from-ios.sh [path/to/BitSigner]`. It refuses to copy a `signer.js` that does not match the iOS `bundle-manifest.json`. It copies the file, updates the pin in `EngineBundle.kt` and prints the digest. **Never edit `signer.js` by hand.**
3. Run `tools/gradle-slot.sh ./gradlew :core:engine:testDebugUnitTest :core:engine:connectedDebugAndroidTest`. The instrumented vectors are BIP84, EVM, TRON and Solana.
4. Commit `signer.js` and `EngineBundle.kt` together, then run `verify-release.sh`: the engine line compares the APK with the pin and with iOS.

The data files have no sync script: the token registry, known contracts, `TokenIcons.pack` and `.index.json`, the BIP39 lists and the engine notices. Copy them from iOS `BitToolSigner/Resources/` byte for byte, and keep them identical. The `data` line fails on any difference.

Publish the engine SHA-256 next to the APK SHA-256 on bittool.com/signer. The app computes the same digest on the device (Open Source › This build), as iOS does.

---

## 7. R8 mapping and native symbols

- **Archive.** Keep `mapping/{apkRelease,playRelease}/mapping.txt` and `native-debug-symbols/*.zip` from the archive (§5), with `SHA256SUMS` and `release-manifest.json`, for as long as the release can be installed. Without them, a stack trace a user sends cannot be read. The mapping depends on the exact build, so archive the files of the build you ship, not of a rebuild.
- **Play.** The AAB already carries its R8 mapping (`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`) and the native symbol tables of the engine, crypto and DataStore libraries (`BUNDLE-METADATA/com.android.tools.build.debugsymbols/`); the `archive` line of `verify-release.sh` checks both, and that the bundled mapping is the archived one. The engine and crypto libraries are built from source, so Play's "missing native debug symbols" warning that ownbit-build-release §7.2.11 expected for prebuilt QuickJS should not appear. The CameraX and AndroidX Graphics libraries are prebuilt without symbols, so their frames stay unsymbolicated. After the upload, check in Play Console › App bundle explorer that the deobfuscation file and the native symbols are listed. Upload `mapping/playRelease/mapping.txt` by hand only if they are missing.
- **Retrace.** Use `retrace mapping.txt stacktrace.txt` (Android SDK cmdline-tools, `cmdline-tools/latest/bin/retrace`) or Android Studio › Analyze Stack Trace. The cmdline-tools are not part of the build prerequisites and were not installed on the 1.0.0 dry-run machine; install them from Android Studio › SDK Manager › SDK Tools › Android SDK Command-line Tools.
- **Release smoke (manual, R8).** Install the release APK on a device. A debug-signed copy from `verify-release.sh` is fine on a test device. Run every flow once in airplane mode, then search logcat:

  ```
  adb logcat -d | grep -E 'ClassNotFound|NoSuchMethod|NoSuchField|SerializationException|UnsatisfiedLink|VerifyError|IncompatibleClassChange|HiltViewModel'
  ```

  Expected: nothing. R8 full mode once broke `@Serializable object` routes with "Serializer for class 'a' is not found" (ownbit-build-release §5).

---

## 8. Legal-text review (manual, legal owner)

**Status: open (backlog B18).** The Android Terms of Use and Privacy Policy are drafts derived from iOS version 1.1. They need sign-off by the legal owner before the first release of either flavor (DECISIONS §9.5).

| File | Notes |
|---|---|
| `app/src/apk/assets/legal/TERMS_OF_USE.txt`, `PRIVACY_POLICY.txt` | Direct download. The Terms contain the `apksigner verify --print-certs` instruction and the published-fingerprint clause. Privacy §10 is "Direct download". |
| `app/src/play/assets/legal/TERMS_OF_USE.txt`, `PRIVACY_POLICY.txt` | Google Play. Terms §15 and Privacy §10 are "Google Play". |
| `core/data/src/main/assets/legal/THIRD_PARTY_LICENSES_ANDROID.txt` | The Android dependency notices (backlog B19): compare with `dependencies-*.txt` of the archive. |
| `core/data/src/main/assets/legal/THIRD_PARTY_LICENSES.txt` | The engine's notices, a copy of the iOS file; `verify-release.sh` checks the identity |

The review covers:

- the Android platform wording: Keystore, TEE / StrongBox, no backup, FLAG_SECURE, the clipboard;
- the distribution clauses;
- the `apk` Terms' verification clause: it says the in-app Signing certificate shows "the same value" as `apksigner`, but the app shows it in the colon-separated uppercase form (§4);
- the claims `LegalTextsTest` enforces: no networking code, "Clear Activity", the two-minute clipboard clearing, the platform-security and backup claims;
- the effective date.

`LegalTextsTest` (in `app/src/test`) also fixes the structure:

- the "Version 1.1" line comes first;
- the Terms have 16 sections ending "16. Contact";
- the only URL is `https://bittool.com/signer`;
- there is no iOS-only wording;
- the two flavors differ only in the distribution clause.

If the legal owner changes the version, the date or the sections, the owner of `app/**` updates the texts and the test together. The Play Console Data safety answers ("no data collected or shared") and the privacy policy URL must match the reviewed policy.

Record the sign-off in §13: reviewer, date, and the SHA-256 of each of the four texts as reviewed.

---

## 9. Translations

**Automated** (`verify-release.sh`, §5):

- the iOS catalogue import is current;
- the iOS catalogue copies are byte-identical with the iOS repository;
- every Android-only string (107 × 20 languages on 1.0.0) is translated and not stale;
- the importer tests pass;
- `AppNameTest` (unit tests) checks "BitTool Signer" in all 21 languages.

**Manual, before the first release:**

- **Native-speaker review of the Android-only strings.** These are the `strings_<area>.xml` files and their `values-*/` translations, generated by `tools/l10n/android_strings.py` from `tools/l10n/android/<lang>.json`. They were translated during the port (backlog B20, commit fd447d2), and no native-speaker review is recorded. Review at least the languages of the main markets, plus Arabic. Fix translations in `tools/l10n/android/<lang>.json` and regenerate. Never edit generated XML.
- **Overrides of iOS translations.** Review `docs/qa/ios-translation-fixes.md` (generated) and send it to the iOS team. When iOS adopts a fix, the importer fails until the override is removed.
- **In-app spot check on a device.** Switch Settings › Language through all 21 languages: no English fallback in translated screens, no clipped text. Check Arabic RTL with Latin digits (`ar-u-nu-latn`) and the legal documents (English only, by decision). Do the Play install check under §11.5: with `bundle.language.enableSplit = false`, every language must work in a Play-installed copy too.

---

## 10. Screenshot tour

The tour is specified in gaps.md G2: shot names paired with iOS, light and dark, public fixtures only, and the device matrix (compact phone, large phone, tablet ≥ 840 dp, font scale 2.0, Arabic).

**Status.** The `:e2e` module of G1–G3 does not exist yet: `settings.gradle.kts` has no `:e2e`, and DECISIONS §9.8 assigns it to the orchestrator. Neither the 18-flow acceptance suite (G1) nor the automated `ScreenshotTour` can run. Until it exists, the tour is manual on the **debug** build, using the debug UI-test harness (G3) through `tools/qa/ui.py`. Release builds cannot be captured because of FLAG_SECURE, which is itself checked in §11.5.

```bash
tools/gradle-slot.sh ./gradlew :app:installApkDebug        # emulator; ui.py drives com.bittool.signer.debug
tools/qa/ui.py launch --fixtures 0                         # fresh run, capture allowed
tools/qa/ui.py shot 01-welcome                             # → $QA_SHOTS (default build/qa-shots)
tools/qa/ui.py tap welcome.create … ; tools/qa/ui.py shot 09-create …   # follow the G2 sequence and names
tools/qa/ui.py launch --fixtures 2 --dark                  # second phase, dark; --locale ar; tools/qa/ui.py font 2.0
tools/qa/ui.py launch --fixtures 2 --keep --lock           # App Lock shots (50-, 51-)
```

- **Sensitive shots** never go into reports, tickets or chat: `09d-backup-qr-revealed`, `09e-recovery-cards-revealed` and `09g-verify-board`. They show the public fixture phrase only.
- **Component screenshots.** The unit-test step records Roborazzi PNGs into `<module>/build/outputs/roborazzi/` for `:app`, `:core:designsystem`, `:core:ui` and the six feature modules. A run restored from Gradle's build cache does not write them. To get fresh PNGs, rerun those tasks, for example:

  ```
  tools/gradle-slot.sh ./gradlew :feature:settings:testDebugUnitTest --rerun
  ```

  Review them next to the tour.
- **Record.** Note the device and API level, light or dark, locale, font scale, the shot directory and any problem filed (§13).

---

## 11. Manual acceptance on real phones (no tool can do these)

Use dedicated test wallets that hold no funds. The reference phrase is the public BIP39 vector (`abandon …about`, fingerprint 73C5DA0A), plus wallets created on the device. Run on the release candidate: the release-signed build, or the debug-signed copy of the same commit for the destructive items. Phones:

- one with StrongBox, for example a Pixel 6 or later, or a Samsung S-series;
- one without StrongBox;
- one at API 26–28;
- one small 32-bit ARM phone if one is available (armeabi-v7a is shipped for old air-gapped phones).

Record every item in §13 with the phone, Android version, build and result.

### 11.1 Third-party wallet round trips (DECISIONS §9.9)

For every wallet the Connect page lists as "can sign by QR", the complete round trip has to succeed on a real device and be recorded with the wallet's version: pair or import the export, check the addresses, build a request in the wallet, scan it, review it, sign it, scan the signature back, and let the wallet verify or broadcast it on a test amount.

The list comes from `CompatibleWallets.kt`. Official documentation does not count as interoperability evidence.

| Export | Wallets that must sign by QR | Watch-only imports to check |
|---|---|---|
| Bitcoin account (`crypto-account`, then `crypto-psbt`) | Sparrow, BlueWallet, Nunchuk, Bitcoin Keeper, BULL Bitcoin, Specter, Bitcoin Safe, ZEUS (no legacy addresses) | BlueWallet, Electrum (address) |
| Bitcoin cosigner (multisig) | Sparrow, BlueWallet, Nunchuk, Bitcoin Keeper, Specter, Bitcoin Safe | — |
| EVM (`crypto-hdkey`, then `eth-sign-request`) | MetaMask, Rabby, imToken, TokenPocket, Ambire | Rabby, TokenPocket, OKX Wallet, Trust Wallet |
| Solana (`crypto-multi-accounts`, then `sol-sign-request`) | Solflare, Helium Wallet | Phantom, Backpack, Jupiter, OKX Wallet, TokenPocket |
| TRON | — (address only) | TokenPocket, TronLink, OKX Wallet, OneKey |
| Wallet level (`bittool-signer-wallet` v1, animated QR: 4 frames for the reference phrase) | BitTool Wallet: one full loop imports 9 accounts (4 Bitcoin address types, EVM, TRON, 3 Solana), and the first address of each equals the verification list on the page | — |

Cover, separately:

- single-sig, multisig, token transfer and token approval (known and unlisted spender);
- message signing and typed data;
- NFT marketplace signatures (Seaport and LooksRare `eth_signTypedData_v4` requests exported from OpenSea and LooksRare).

Unsupported spends must be refused, not half-parsed. These are BTC MuSig2, a Solana address lookup table without trusted context, and an arbitrary multisig proposal (the iOS `docs/Release-1.0.0.md` boundaries).

### 11.2 App Lock and biometrics

App Lock uses `BiometricPrompt` with `BIOMETRIC_STRONG` only, and "Use a Wallet Password" as the negative button. The background grace is 120 s, measured with `elapsedRealtime`. The app is locked on a cold launch and fails closed (DECISIONS §6). Check:

- [ ] Enabling App Lock needs an enrolled strong biometric. Cancelling the prompt leaves App Lock off. On a phone whose only biometric is a weak (class 2) face unlock, enabling App Lock must fail with an explanation, not silently. Record the text shown.
- [ ] A cold launch locks and prompts at once. "Use a Wallet Password" unlocks with the password of any wallet.
- [ ] 110–119 s in the background: still unlocked, same page. 120 s or more: locked (`DefaultAppAccessController`: unlocked only while less than 120 s have passed). Background, power button, about 3 minutes of real sleep, then return: locked, because sleep counts.
- [ ] Five failed fingerprints cause a lockout. The prompt reports it, and the wallet password still unlocks.
- [ ] Delete a wallet with App Lock on: biometric first, then the password. Cancelling the biometric aborts. With App Lock off: password only.
- [ ] App Lock never authorizes a signature: every signature asks for its wallet's password. A half-typed signing password and the risk acknowledgement are cleared when the app goes to the background.
- [ ] Enroll an additional fingerprint, or remove all of them: App Lock behaves as above (no crash, no silent unlock).

### 11.3 The lost-key path (Android-only; ARCHITECTURE §6.18, backlog B9, L1–L5)

On the emulator, QA round 2 saw that removing the screen lock deletes every Keystore key created with `setUnlockedDeviceRequired`, and the enrolled fingerprints with them. Confirm on a Pixel and on a Samsung. The expected behaviour is the one the build under test implements. The lost-key recovery work (L1–L5) is in progress; run this item on the release candidate that contains it.

A rehearsal on the debug build needs no phone: `tools/qa/ui.py launch --fixtures 2`, then `tools/qa/ui.py launch --lose-keys` deletes the run's fixture wallet keys, so every wallet has a lost device key (step 3 onwards). It does not replace the real-phone run, which is the only one that exercises the platform's key deletion.

1. Create a wallet on the device and import the reference phrase as a second wallet. Sign once with each. Turn App Lock on.
2. Settings › Security › Screen lock › None.
3. Reopen the app:
   - the lock screen explains why it cannot unlock and lets the person continue (L2);
   - both wallets show "This Wallet Can’t Be Unlocked" with Remove, on the card and on the detail page (L4);
   - Activity starts a fresh journal and says so once (L1);
   - no phrase can be revealed and nothing can be signed.
4. Remove the wallet created on the device with Remove This Wallet: no password, its own "Remove This Wallet?" dialog (a biometric check comes first only when App Lock is on and a strong biometric is still enrolled). The reference wallet stays until it is removed too.
5. Set a screen lock again. Import the reference phrase while its lost-key wallet is still listed. Expected (L3): no second wallet with the same keys; the app points to Remove This Wallet for the lost one. Remove it and import again: master fingerprint 73C5DA0A, the same addresses, and signing works.
6. Unlike iOS (Device-Self-Test A1, step 4), restoring the screen lock does **not** bring a lost-key wallet back on Android, because its key is gone. Only the recovery phrase brings it back. The release notes and support texts must say this.

### 11.4 StrongBox

Wallet device keys (AES-256-GCM) are generated in StrongBox when the phone reports `FEATURE_STRONGBOX_KEYSTORE`, and fall back to the TEE otherwise. The public-data HMAC key stays in the TEE by design, because it runs on every reload (`KeystoreKeys.kt`). Check:

- [ ] On a StrongBox phone with a screen lock, check the feature with `adb shell pm list features | grep -i strongbox`. Then run `ANDROID_SERIAL=<serial> tools/gradle-slot.sh ./gradlew :core:crypto:connectedDebugAndroidTest` and look at `adb logcat -s KeystoreTest`. It must show "production device key on a secure device: StrongBox", and "StrongBox requested on a device with StrongBox: StrongBox". The test can read the security level only on API 31 or later; below that it logs `insideSecureHardware=true`, which does not tell StrongBox from the TEE, so use an Android 12 or later phone for this item.
- [ ] On a phone without StrongBox, the same run shows "TEE" (API 31+) or `insideSecureHardware=true`, and create, import and sign work.
- [ ] On the StrongBox phone, time create, unlock and sign with the release build. StrongBox is slow, so progress must stay visible and nothing may freeze or ANR.
- [ ] API 26–27 has no StrongBox API: the TEE path works.

### 11.5 Device self-test (adapted from iOS `release/Device-Self-Test.md`)

- [ ] No screen lock: creating a wallet is refused with the screen-lock message.
- [ ] Reinstall: uninstalling removes every wallet. Re-importing the phrase gives the same fingerprint and addresses.
- [ ] No backup: `adb shell bmgr backupnow com.bittool.signer` backs nothing up (`allowBackup=false`, extraction rules exclude everything), and a device-to-device transfer carries no wallet.
- [ ] FLAG_SECURE on the **release** build:
  - a screenshot is black or refused;
  - screen recording and casting show black;
  - the Recents thumbnail is blank;
  - `adb exec-out screencap` returns no image.
- [ ] Overlays: with a test overlay app (`SYSTEM_ALERT_WINDOW`) showing, the decisive buttons (Sign, reveal, delete, confirm) refuse obscured taps, and overlays are hidden on API 31+ (gaps.md G4).
- [ ] Camera:
  - real scanning in poor light, from a glossy screen, with shaking hands, near and far;
  - animated multi-part QR codes, with progress visible;
  - permission denied, then recovered from Settings;
  - Done after a signature never reopens the camera.
- [ ] Airplane mode throughout: every flow works, and no prompt or error asks for a connection.
- [ ] Light and dark, the largest font, TalkBack (amounts, addresses, risk texts), a small screen, a tablet, landscape, Arabic RTL.
- [ ] Under option A (§1): install the `apk` build, create a wallet, then install the Play internal-testing build of the same or a higher versionCode over it. It must update in place with the wallet intact; then do the reverse.
- [ ] Play install: a language switch works for every language. There is no language split, so a missing language here means a packaging fault.
- [ ] The flavor content, on each install. The screenshot tour (§10) covers only the `apk` debug build, so check the `play` build here:
  - Settings › Fully Open Source › This build. The `apk` release shows Distribution "Direct download (APK)", and its Signing certificate equals the published fingerprint (§4). The Play internal-testing install shows "Google Play", and the Play app signing certificate, which equals the `apk` one under option A.
  - Terms of Use and Privacy Policy. The `apk` build shows the direct-download clauses, with the `apksigner` verification steps. The Play build shows the Google Play clauses (Terms §15, Privacy §10).

---

## 12. Publishing

**`apk` channel (bittool.com/signer):**

1. Upload `BitToolSigner-<version>-<code>-apk.apk` and its line from `SHA256SUMS`.
2. Publish, on the same page:
   - the certificate SHA-256 in both formats (§4), unchanged across releases;
   - the APK SHA-256;
   - the engine SHA-256;
   - the steps to verify the APK (`apksigner verify --print-certs` on a computer, then Settings › Fully Open Source › This build after installing).
3. The page is the only URL the legal texts contain (`https://bittool.com/signer`), so it must be live before the release.

**`play` channel:**

1. Upload the AAB to internal testing first. Run §11.5's Play items on the internal-testing install.
2. Check in App bundle explorer:
   - the app signing certificate SHA-256 (§1);
   - the deobfuscation file and the native symbols (§7);
   - the ABIs (arm64-v8a, armeabi-v7a, x86_64);
   - all 21 languages in the base module.
3. Store listing and policy:
   - Data safety: no data collected, no data shared.
   - Permissions declarations: camera only, as user-facing.
   - Privacy policy URL: `https://bittool.com/signer`.
   - Financial features declaration: a non-custodial cryptocurrency wallet. Re-read the current Play policy for cryptocurrency exchanges and software wallets before each release.
   - The other App content answers: no ads; app access needs no account or login; the content rating questionnaire; a target audience of adults only (the app is not designed for children).
4. Promote to production with a staged rollout.

**Both channels:**

1. Tag the verified commit: `git tag -s android-v<version>`. Push the tag with the release.
2. Keep the whole `build/release/BitToolSigner-<version>-<code>/` directory, with the §13 record, in the release archive. Do not keep the debug-signed one.

---

## 13. Release record (fill in for every release)

```
Release:            BitTool Signer for Android <versionName> (<versionCode>)      commit: ____________   tag: android-v____
verify-release.sh --release:  PASS on ____-__-__  (attach summary.txt and release-manifest.json)
Signing:            apk cert SHA-256 ____________________   Play app signing cert SHA-256 ____________________ (equal under A)
                    compared with SIGNER_RELEASE_CERT_SHA256: [ ] yes  [ ] no (first release only)
Engine SHA-256:     ____________________ (= iOS engine/bundle-manifest.json)
Legal sign-off:     reviewer ________  date ____  sha256 apk Terms ____ Privacy ____ / play Terms ____ Privacy ____
Translations:       languages reviewed by native speakers: ________________________
Screenshot tour:    devices / modes ________________  shot directory ________  issues ________
§11.1 round trips:  table: wallet | version | platform | chain | entry point | operation | result | date | tester
§11.2 App Lock:     phone / Android / result per item
§11.3 lost key:     Pixel ____ result ____   Samsung ____ result ____
§11.4 StrongBox:    StrongBox phone ____ level ____   non-StrongBox phone ____ level ____
§11.5 self-test:    phone / Android / result per item
R8 smoke:           logcat clean on ____ (phone / API)
Open backlog items accepted for this release (docs/qa/backlog.md), with the reason: ________________
```

---

## 14. Status on 2026-10-21 (dry run on main, commit d9f37ab)

I ran `tools/release/verify-release.sh` on the current main:

- **Without a release key:** PASS, debug-signed and labelled. Every check passed except the expected warnings: debug signing, and an uncommitted tree during development.
- **With throw-away local test keys, release plus upload (since deleted):** PASS. The signing modes, the certificate comparison and the separate play upload certificate behaved as specified.

Facts recorded by that run:

- **Version:** 1.0.0 (1000001), = iOS `MARKETING_VERSION` 1.0.0; BUILD 1 = iOS `CURRENT_PROJECT_VERSION` 1. minSdk 26, targetSdk 36, ABIs arm64-v8a, armeabi-v7a and x86_64.
- **Tests and lint:** 1,829 unit tests in 19 test tasks, 0 failures. Lint: 0 errors and 71 warnings per release variant, mostly `UseTomlInstead`, `NewerVersionAvailable` and `PluralsCandidate`.
- **l10n:** the iOS catalogue import and its copies are up to date; 107 Android-only strings × 20 languages are present.
- **Offline:** check-offline is clean for the `apk` APK, the `play` APK and the AAB base.
- **Native libraries:** 54 `.so` files (6 libraries × 3 ABIs × the 3 artifacts), all aligned to 16 KB, including 32-bit ones. The APKs pass `zipalign -P 16`.
- **Engine and data:** the engine digest `6933d0d8…f21077` is identical in all three artifacts, in the pin and in iOS. The 15 data files equal the iOS resources, and the legal texts are the flavor files.
- **Permissions:** exactly the G8 set.
- **Assets:** 19 signer assets, plus the baseline profile.
- **Sizes:** APK 20.7 MB, AAB 24.2 MB.
- **Manifest flags and Play metadata:** none of the three artifacts is debuggable or `testOnly`, and all have `allowBackup="false"`. The AAB carries the R8 mapping, identical to the archived one, and the native symbol tables of the engine, crypto and DataStore libraries.

**Review of the script (chore/release-checklist-reviewed).** Every check was broken on purpose, one at a time, in a scratch copy: a `git archive` export plus copies of `app/build/outputs` and `app/build/reports`, and a copy of the iOS files the script reads. The repository itself was never changed. Each break turned its own line FAIL and gave exit status 1:

- the engine pin, the iOS engine manifest, an iOS data file, an iOS translation and the iOS version (WARN, FAIL with `--release`);
- a flavor legal text, the Gradle permission report (an extra INTERNET, a missing report), the component and harness reports;
- the notices: a removed entry, a new library at version 2.0, a new library called `core`;
- the size budget, the versionName, `--build-number`, a missing or changed R8 mapping, and missing native symbols;
- in the APKs: a changed `signer.js`, a stray `test_*.js`, deflated `.so` files, one `.so` with a 4 KB `p_align` patched in place (alignment otherwise intact), and a `java/net/Socket` reference in the dex;
- a missing AAB, a truncated AAB and a truncated APK;
- the l10n checks: a generated `strings_<area>.xml`, a generated `strings_ios.xml`, and a failing importer test;
- the signing configurations: a partial release key, an upload key without a release key, and a release key set up with debug-signed outputs and the wrong `SIGNER_RELEASE_CERT_SHA256`;
- `--release` on a debug-signed build, on a copy that is not a git checkout, and with `--ios none`.

A full run on a second scratch copy combined three breaks: INTERNET in the app manifest, a failing `:core:model` test, and a lint NewApi error. It reported the build (`checkApkReleasePermissions`), the unit tests (1 of 1,830 failed) and lint (1 error per variant) as FAIL, and skipped the artifact checks as not built.

Gaps the review found and fixed in `tools/release/`:

- a copy outside git passed "clean tree";
- the notices check accepted any library whose version appeared anywhere in the file, for example "2.0" from "Apache-2.0", or whose artifactId was a generic word such as `core`;
- missing native symbols were silently left out of the archive;
- a `--checks-only` run replaced the archive of a full run;
- a wrong iOS path produced a list of confusing failures instead of a usage error;
- `check-16kb.py` crashed on an unreadable artifact instead of reporting it;
- the debuggable, `testOnly` and backup flags of the release manifests were not checked.

Open items that block or condition the first release. Each one is owned outside `docs/release.md` and `tools/release/`:

1. The **signing-key decision** (§1): owner.
2. The **legal review** of the Android texts (§8, B18), and the notices refresh against the final dependency set (B19): legal owner. The automated notices check passes for every non-AndroidX library.
3. **`:e2e`** (G1–G3) does not exist: no automated acceptance suite and no automated screenshot tour (§10). Orchestrator.
4. The **lost-key recovery** items L1–L5 are in progress in a parallel workflow; §11.3 must be run on the build that contains them.
5. The **native-speaker review** of the Android-only translations (§9).
6. **All of §11** on real phones.
7. The APKs are v2-signed only (§4). This is not a defect; it is a note for the owner of `app/**` if v3 is wanted.
8. The **signing-certificate format** (§4, §8). The app shows the digest as uppercase colon-separated hex, while the `apk` Terms promise "the same value" that `apksigner` prints in lowercase. This is for the owner of `app/**` and the legal review: change the in-app format or the Terms wording.
