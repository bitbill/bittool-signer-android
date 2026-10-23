# BitTool Signer for Android — binding decisions

Every implementer reads this first. The specs in this folder describe *what* the iOS app does; this file fixes *how*
the Android port is built. Where a spec recommends something different, this file wins; where this file is silent, the
spec's "Android mapping" section is the default.

## 1. Product scope

- Functionally identical to BitTool Signer for iOS 1.0.0 (`/Users/tanguopeng/Documents/bitsigner-ios/BitSigner`), with
  Android-native UI polish. iOS-only items are dropped (Face ID wording and permission prompt, Liquid Glass, iOS 17/18
  fallbacks, simulator UI-test context, App Store specifics, PrivacyInfo.xcprivacy).
- App name "BitTool Signer" in every language. applicationId `com.bittool.signer` (debug: `.debug`). Version 1.0.0.
- Two distributions: product flavors `apk` (direct download from bittool.com/signer) and `play` (Google Play), i.e.
  `apkRelease` / `playRelease`. Both are offline and functionally identical; they differ only in distribution-specific
  About/Open Source content (`BuildConfig.DISTRIBUTION`), signing (Play upload key) and packaging (App Bundle for play).

## 2. Toolchain (already scaffolded, builds green)

Gradle 9.7.1, AGP 9.4.1 with **built-in Kotlin** (never apply `org.jetbrains.kotlin.android`), Kotlin 2.4.21, KSP 2.3.12,
JDK 21 (daemon criteria in `gradle/gradle-daemon-jvm.properties`), compileSdk 37, targetSdk 36, minSdk 26.
Compose BOM 2026.09.00 (Material 3 1.4.0), Navigation Compose 2.10.2 (type-safe `@Serializable` routes),
Hilt 2.60.1 + hilt-navigation-compose 1.4.0, kotlinx.serialization 1.12.0, coroutines 1.11.0, DataStore 1.2.1,
CameraX 1.6.2, ZXing core 3.5.4, QuickJS 2026-06-04 vendored in `core/engine/src/main/cpp` (own JNI, NDK 28.2,
CMake 3.22.1, 16 KB-aligned), androidx.biometric 1.1.0,
core-splashscreen 1.2.0, AppCompat 1.8.0 (per-app locales). Versions live in `gradle/libs.versions.toml` only.

Build commands (always through the slot limiter; JAVA_HOME is set by it):

```
tools/gradle-slot.sh ./gradlew :core:model:test
tools/gradle-slot.sh ./gradlew :core:crypto:testDebugUnitTest
tools/gradle-slot.sh ./gradlew :feature:sign:compileDebugKotlin
tools/gradle-slot.sh ./gradlew :app:assembleApkDebug
tools/gradle-slot.sh ./gradlew :core:engine:connectedDebugAndroidTest   # emulator-5554 is running
```

## 3. Modules and dependency rules

| Module | Owns |
|---|---|
| `:core:common` | dispatchers qualifiers, `AppClock`/monotonic clock, small utilities (hex, grapheme count, UTF-8 size), logging that is a no-op in release |
| `:core:model` | pure Kotlin/JVM: every domain type and engine-result reader (models-state.md), `AddressFormatter`, recovery cards, backup verification, compatible-wallet data, `AppLanguage` enum, password policy rules if pure |
| `:core:l10n` | ALL user-visible strings (generated `strings_ios.xml` + Android-only `strings_<area>.xml`), `IosStrings` lookup, `ReviewText` translation helpers, locale application helpers |
| `:core:designsystem` | `SignerTheme`, colour/typography/shape/motion tokens, generic components (no domain types), brand mark/tile, chain glyph vectors |
| `:core:engine` | the QuickJS host and `SignerEngine` (done); engine-level tests and vectors |
| `:core:crypto` | vault format v2, PBKDF2/AES-GCM/Keystore device key, public-index HMAC, BIP39 (10 languages, NFKD, detection, ambiguity, SeedQR/phrase QR parsing), native seed derivation, password policy, secure random, secret zeroing |
| `:core:data` | `SignerStore` (= iOS `AppStore`), wallet repositories, receipt (Activity) store, preferences (DataStore), token registry, known contracts, token icon pack, legal texts |
| `:core:security` | App Lock (`AppAccessController`), biometric adapter, lifecycle/capture/screenshot monitors, recovery privacy sessions, lock window + window gate, secure-window helpers |
| `:core:scanner` | CameraX + ZXing QR scanning (composable + analyzer), QR encoding to bitmaps, animated multi-frame QR player |
| `:core:ui` | domain-aware shared composables: network/chain logos, token icon, wallet avatar & app icons, address/hash text, QR card, payload disclosure, password fields + strength meter, legal document view, review building blocks shared by sign + activity |
| `:core:navigation` | `@Serializable` route types, `Tab`, `AppRouter` (iOS AppRouter), setup intent/presentation types |
| `:feature:onboarding` | Welcome, onboarding pager |
| `:feature:setup` | create / import / backup (words, QR, recovery cards, tips) / verify / password / naming flows, reveal-phrase flow |
| `:feature:wallets` | Wallets tab, wallet detail, addresses, Connect (pairing), wallet security sheets (rename, change/reset password, delete) |
| `:feature:sign` | Scan tab, scanner screen, paste / import file, request review, per-task password, signature result |
| `:feature:activity` | Activity tab, receipt detail (re-review, response QR, export, raw text) |
| `:feature:settings` | Settings tab and every page under it (App Lock row, appearance, language, networks, tokens & contracts, compatibility, air-gapped guide, open source, legal, about) |
| `:app` | `SignerApplication`, `MainActivity`, root stage machine, tab scaffold, NavHost wiring, lock/cover layers, global alert, splash gate, flavor-specific code (`src/apk`, `src/play`) |

Rules: features never depend on other features (navigate through `:core:navigation`). Nothing depends on `:app`.
`:core:model` stays Android-free. Use `api(...)` only where a module's public signatures expose the other module's types.

Package root: `com.bittool.signer.<module path>` (e.g. `com.bittool.signer.core.data`, `com.bittool.signer.feature.sign`).

## 4. Strings and languages

- Never hard-code user-visible text in Kotlin. Every iOS string already exists as a resource in `:core:l10n`
  (`core/l10n/src/main/res/values*/strings_ios.xml`, 782 strings × 21 languages, generated by
  `tools/l10n/import_ios_strings.py`; `tools/l10n/sync-from-ios.sh` copies a newer iOS catalogue and regenerates them,
  and lists the keys iOS added, whose Android-only stand-ins then give way to them). Find a resource name by its English text in `tools/l10n/strings-index.tsv`
  (`grep -F` the exact English). Use `com.bittool.signer.core.l10n.R.string.<name>` with `stringResource(...)` /
  `context.getString(...)`; `%@` became `%s`, `%lld` became `%d` (positional when there are two or more).
- Text produced by the engine (field labels, display actions, review titles) is translated at draw time like iOS
  `ReviewText`: `IosStrings.lookup(english)` returns the resource id or null (then show the English unchanged).
- New Android-only text (e.g. Android wording of the air-gapped guide, biometric prompt texts that differ, "Open
  settings", screen-lock requirement, APK certificate fingerprint): add it in English to
  `core/l10n/src/main/res/values/strings_<area>.xml`, one file per feature area (`strings_setup.xml`, `strings_sign.xml`,
  …) so parallel work never conflicts, names prefixed with the area (`setup_…`). A later pass translates these into the
  20 other languages. Keep them few: prefer the iOS string whenever its meaning fits.
- Per-app language via `AppCompatDelegate.setApplicationLocales` (manifest already has `AppLocalesMetadataHolderService`
  with `autoStoreLocales`). Arabic is applied as `ar-u-nu-latn` (Latin digits), RTL layout follows automatically.

## 5. Engine

`SignerEngine` (`:core:engine`) is implemented and verified on the emulator (BIP84/EVM/TRON/Solana vectors match):
own minimal JNI over vendored QuickJS 2026-06-04 (UTF-8 byte arrays in and out — no JNI modified-UTF-8 problems);
fresh runtime per secret/untrusted operation, created **without the Eval intrinsic** (engine code cannot `eval` or
`new Function`), one warm runtime for `derivePublicAddresses` / `createPublicPairing` / `createWalletPairing`
(discarded after a native failure), one scan-session runtime, single dedicated engine thread, 1 MiB request and
2 MiB response limits, sorted-key JSON, iOS-equivalent entropy bootstrap (bionic `arc4random_buf`), SHA-256 check of
the shipped `signer.js`, bytecode compiled once in memory and never persisted, `prewarm()` and `bundleDigest()`.
Callers pass `kotlinx.serialization.json.JsonObject` parameters and get the engine `result` object back (non-object
results wrapped as `{"value": …}`), or an `EngineException(code, message)`. Typed parsing of results lives in
`:core:model`. Measured on the emulator: verify + compile ≈ 0.3 s (call `prewarm()` after launch), fresh runtime
≈ 40 ms, `derivePublicIndex` from a seed ≈ 0.65 s (every `signPlanTask` re-derives it internally) — still show
progress for create/import/repair/sign and never call the engine from the main thread.

## 6. Security posture (Android equivalents, decided)

- No `INTERNET` permission (manifest removes it even if a library adds it). No network code anywhere.
- `FLAG_SECURE` on the activity window from `onCreate`, never toggled (blank Recents thumbnail, no screenshots or
  recordings). A debug-only switch may disable it for the screenshot tour. API 33+: `setRecentsScreenshotEnabled(false)`.
- App Lock: `BiometricPrompt` with `BIOMETRIC_WEAK` (owner decision 2026-10-22: Class 2 face unlock such as Huawei's is accepted, because App Lock only gates public pages; signing, reveal and delete still need the wallet password) and the negative button "Use a Wallet Password" (iOS
  semantics); 120 s background grace measured with `SystemClock.elapsedRealtime()`; locked on cold launch; fail closed
  while preferences load. App Lock never authorizes a signature.
- Vault: PBKDF2-HMAC-SHA256 600,000 iterations → AES-256-GCM, then an outer AES-GCM layer under a non-exportable Android
  Keystore key (StrongBox when available); public index authenticated with a Keystore HMAC-SHA256 key; files in
  `noBackupFilesDir`, written atomically; `allowBackup=false` + data extraction rules exclude everything.
- Secrets in `ByteArray`/`CharArray`, zeroed after use; never in logs, `toString`, saved state, navigation arguments or
  `SavedStateHandle`. Background (`onStop`, not during configuration changes) clears passwords, risk acknowledgements and
  revealed phrases immediately.
- Secret/phrase fields: no personalized IME learning, no autocorrect/suggestions, autofill excluded app-wide.
- Clipboard: addresses/xpubs only, marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`); never secrets.

## 7. Architecture conventions

- UI: Jetpack Compose + Material 3, single activity (`MainActivity : AppCompatActivity`), edge-to-edge, predictive back
  (`enableOnBackInvokedCallback`), splash screen API. Screens are `@Composable fun XxxScreen(...)` + `@HiltViewModel`
  `XxxViewModel` exposing `StateFlow<XxxUiState>`; collect with `collectAsStateWithLifecycle()`.
- State: `SignerStore` is the single source of truth for wallets, the request pipeline and activity (iOS `AppStore`),
  `@Singleton`, state as `StateFlow<SignerState>`, mutations confined to the main thread, epoch checks after every
  suspension (models-state.md §5). Vault work on `Dispatchers.Default`, file I/O on `Dispatchers.IO`, engine on its thread.
- DI: Hilt everywhere. The app provides `@Named(EngineModule.ENGINE_TEST_OPERATIONS) Boolean = BuildConfig.DEBUG`.
- Navigation: one `NavHost` in `:app`; each feature exposes `fun NavGraphBuilder.xxxGraph(...)` plus screen entry
  points; routes are `@Serializable` types in `:core:navigation`. No secrets or large payloads in routes (pass ids;
  read the request from `SignerStore`).
- Quality bar: "looks and feels like a flagship app from a top-tier company" — consistent spacing, motion (shared-axis /
  fade-through transitions, springs without bounce for layout), haptics on meaningful actions, dark mode first-class,
  RTL-correct, large-font-safe, TalkBack labels, 48 dp touch targets, no jank (heavy work off the main thread, stable
  lambdas/keys in lazy lists).
- Tests: port the iOS unit tests that apply (`BitToolSignerTests/*`) as JVM tests (`:core:model:test`,
  `testDebugUnitTest`), Robolectric where Android types are needed, instrumented tests for engine/Keystore.

## 8. Parallel work protocol

- Implementation agents work in their own git worktree branch and touch **only the paths their task owns** (their
  modules, their `strings_<area>.xml`). Shared files (`settings.gradle.kts`, `gradle/libs.versions.toml`,
  `build-logic/**`, `app/**` unless the task owns `:app`, other modules) are read-only; if a dependency is missing from
  the catalog, add it in the module's own `build.gradle.kts` with a full coordinate and say so in the report.
- Commit with a descriptive message in the worktree before finishing; the orchestrator merges branches.
- A task is done only when its modules compile and their tests pass; report anything stubbed, skipped or unverified.

## 9. Answers to the open questions of gaps.md (binding)

1. **Repository address.** The Open Source page shows the Android repository
   `https://github.com/bitbill/bittool-signer-android` (`BuildConfig.REPOSITORY_URL`) as "this app's source", and names
   the shared engine source (the iOS repository `https://github.com/bitbill/bittool-signer-ios`, `engine/`) as where
   `signer.js` is built reproducibly. Text only; the app opens no browser.
2. **The ~60 non-catalogue iOS strings (G6) are translated** into all 21 languages like every other string (Android-only
   strings pass). Implementers add them in English to their `strings_<area>.xml` with the exact iOS English text.
3. **Activity journal:** Keystore-encrypted `receipts.bin` (AES-GCM under a dedicated non-exportable Keystore key) in
   `noBackupFilesDir`; same JSON content and retention rules as iOS `receipts.json`.
4. **Screenshot wording:** keep the iOS state machines (they still fire when FLAG_SECURE is off in debug builds or an
   OEM ignores it) and reword "Photos" for Android ("…delete it from your gallery and any synced copies…") as
   Android-only strings.
5. **Legal texts:** `apk` and `play` get Android versions of the Terms and Privacy Policy derived from the iOS texts
   (App Store clauses replaced by Google Play / direct-download clauses), placed in the flavor source sets, and marked
   for legal-owner review in the release checklist. Parser, structure and tests are shared.
6. **Entropy:** bionic `arc4random_buf` (ChaCha20, kernel-seeded) is the engine's CSPRNG; Kotlin code uses
   `SecureRandom`. Keep the Taproot "two signatures differ" vector.
7. **Overlay and accessibility hardening (G4/G5): wanted.** `setHideOverlayWindows(true)` on API 31+ (permission
   `HIDE_OVERLAY_WINDOWS`), `filterTouchesWhenObscured` on decisive buttons (Sign, reveal, delete, confirm), and
   `setAccessibilityDataSensitive` (API 34+) on revealed recovery words and passwords.
8. **`:e2e` module (G1–G3):** owned by the orchestrator during integration (shared Gradle files).
9. **Third-party wallet round trips:** a pre-release acceptance item on physical devices; not automatable here.
10. **README staleness:** follow the code (the air-gapped device guide lives in onboarding only).

## 10. Owner decisions left open by the contracts review (binding)

- Default build number is **1**, so Settings shows "1.0.0 (1)" like iOS (`-Psigner.buildNumber` overrides).
- Activity hides "Clear Activity" when the record file cannot be read (iOS parity); `activityUnreadable` drives the
  explanatory state.
- Every compatible-wallet entry stays exactly as the iOS data, except "BitTool Wallet": "iOS · Android" (it ships on
  both; owner, 2026-10-24).
- Product and protocol names the iOS catalogue leaves untranslated (Taproot, Account XPUB, …) stay untranslated.
- No Android-only "Allow camera" label: the camera-permission UI uses the iOS strings plus the system dialog.

## 11. User requirements added during the port (binding)

- **No network, ever.** No network code, no network permission, no network operation of any kind, and no in-app
  update or install mechanism, in either flavor. Enforced three ways: the merged-manifest allowlist in
  `app/build.gradle.kts` (`check*Permissions`), `tools/release/check-offline.sh` on every release APK (permissions,
  dex references to java.net / SSL / WebView / DownloadManager / PackageInstaller / Play services / Firebase / Play
  Core, socket symbols in native libraries), and code review. Verified on 1.0.0 apkRelease and playRelease: clean.
- **In-app BIP39 keyboard (following Ownbit 5.0)** is the default for every recovery-phrase entry: the field has no
  IME connection while it is up; a globe key switches to the system keyboard after a warning (needed for Chinese,
  Japanese and Korean phrases). This is an Android-only addition with no iOS counterpart.
- **One engine.** `signer.js` is the iOS file, copied byte for byte by `tools/engine/sync-from-ios.sh` and pinned by
  `EngineBundle.EXPECTED_SHA256` (EngineDigestTest + runtime check). There is no second JS engine or bundle.
