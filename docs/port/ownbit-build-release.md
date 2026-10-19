# Ownbit 5.0 build, release, flavors, l10n and app shell — what BitTool Signer Android copies

Status: spec for implementers. Scope: Gradle layout and convention plugins, version catalog, `apk` / `play` flavors,
signing, R8, lint, the release checklist, string import from iOS, per-app language, `Application` / `Activity`
setup, and secure-window patterns. Feature behaviour is in the sibling specs (`app-shell.md`,
`activity-settings.md`, `vault-crypto.md`, `scan-sign.md`, …); this file links to them instead of repeating them.

Path prefixes used in citations:

| Prefix | Root |
|---|---|
| `OB:` | `/Users/tanguopeng/AndroidStudioProjects/ownbitandroidnew` (Ownbit 5.0 Android, the reference) |
| `IOS:` | `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner` (BitTool Signer iOS, the product being ported) |
| `SIG:` | `/Users/tanguopeng/Documents/bitool-signer-android` (this repo; a scaffold already exists, see §0) |

---

## 0. Most important facts and risks (read first)

1. **Copy Ownbit's structure, not its versions.** Ownbit runs on AGP 8.9.3, Kotlin 2.1.0, KSP 2.1.0-1.0.29, Compose
   BOM 2024.12.01, Hilt 2.54 and Gradle 8.11.1 (`OB:gradle/libs.versions.toml:1-7`,
   `OB:gradle/wrapper/gradle-wrapper.properties`). The signer uses AGP 9.4.1 with **built-in Kotlin**, Kotlin
   2.4.21, Gradle 9.7.1, KSP 2.3.12, Hilt 2.60.1 and Compose BOM 2026.09.00 (`SIG:gradle/libs.versions.toml:1-26`).
   Every Ownbit convention plugin applies `org.jetbrains.kotlin.android`
   (`OB:build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt:14`,
   `AndroidLibraryConventionPlugin.kt:13`). Under AGP 9 that line must go, along with the other Ownbit-only lines
   listed in §2.8.
2. **The only Kotlin use of `BuildConfig.DISTRIBUTION` in Ownbit is in `:app`**
   (`OB:app/src/main/kotlin/io/ownbit/app/di/AppConfigModule.kt:28`). It becomes `AppConfig.distribution`. Behaviour
   then differs by **which Hilt module each flavor binds** (`src/apk/.../DistributionModule.kt` vs
   `src/play/.../DistributionModule.kt`), not by `if` checks in features. The signer should do the same.
3. **Neither of Ownbit's real flavor differences carries over.** Those are the in-app APK updater with
   `REQUEST_INSTALL_PACKAGES` + FileProvider in `apk`, and "Rate on Google Play" in `play`. The signer has no network
   and never opens another app, so it needs neither. Its flavors differ only in legal text and in build-identity
   rows (distribution name, signing-certificate SHA-256, an APK verification guide in `apk`). The engine stays
   byte-identical and the INTERNET permission is absent in both (§11).
4. **Signing-key decision before the first Play upload.** Ownbit's Play App Signing got a Google-generated key, so
   the Play install and the APK install have different certificates. They cannot update each other, and switching
   channels means uninstalling (`OB:docs/qa/release.md:253-268`). **For the signer, uninstalling deletes the vault.**
   Recommendation: upload the `apk` release key to Play App Signing (PEPK) and sign uploads with a separate upload
   key. Both channels then share one certificate and one published fingerprint (§7.4).
5. **Per-app language below API 33 only works in an `AppCompatActivity`.** Ownbit's `MainActivity` is one
   (`OB:app/src/main/kotlin/io/ownbit/app/MainActivity.kt:48`), and it declares `AppLocalesMetadataHolderService` with
   `autoStoreLocales` (`OB:app/src/main/AndroidManifest.xml:59-67`). Two more requirements:
   `bundle.language.enableSplit = false` (`OB:app/build.gradle.kts:193-196`) and an explicit locale filter list that
   names every regional directory (`OB:app/build.gradle.kts:113-120`). If any is missing, a language silently falls
   back to English, either in the Play install or in every install.
6. **Engine text is translated by English lookup at draw time** (`IOS:BitToolSigner/Models/ReviewText.swift:12-54`).
   The Android catalogue must keep each English key **byte-exact** (U+2019 apostrophes, U+2026 "…", U+2011,
   U+00B7) and needs a runtime English→`R.string` map. The scaffold generates one (`IosStrings.kt`). It must never be
   resolved through `getIdentifier`, because R8's resource shrinker would strip those names.
7. **Configuration cache.** The scaffold turns it on (`SIG:gradle.properties`). Ownbit's
   `pre*ReleaseBuild { doFirst { … } }` checks reference build-script top-level values
   (`OB:app/build.gradle.kts:251-281`), and the configuration cache cannot serialize the script object. The checks
   must be rewritten (§3.9).
8. **Packaging pitfalls in the scaffold.** `packaging.resources.excludes` does **not** remove the QuickJS AAR's
   `assets/test_*.js`, because it filters Java resources, not Android assets (§3.7). The scaffold's R8 rules also
   lack two rules that Ownbit found in device smoke tests: `INSTANCE` for `@Serializable object` routes and the
   `@HiltViewModel` keep rule (§5).
9. **16 KB pages.** QuickJS `wang.harlon.quickjs:wrapper-android:3.2.3` ships stripped `.so` files, and the
   arm64 one is 16 KB aligned. This was verified with `OB:tools/check_16kb.py` on the cached AAR:
   `OK 16384 jni/arm64-v8a/libquickjs-android-wrapper.so`. Keep the check in the release checklist anyway (§7).
10. **Do not copy Ownbit's portrait lock** (`OB:MainActivity.kt:56-59`). The iOS signer allows landscape on iPhone
    (`IOS:BitToolSigner.xcodeproj/project.pbxproj:436`).

---

## 1. Scaffold status in this repo (what already follows Ownbit, what does not)

A lead scaffold already exists in `SIG:` (settings, catalog, build-logic, `:app` build script, manifest,
`tools/l10n/import_ios_strings.py`, generated `core/l10n` resources, `core/engine/src/main/assets/engine/signer.js`).
It mostly mirrors Ownbit. The table below lists where it deviates and what this spec recommends. Each row is
detailed in the section named.

| Item | Scaffold | Recommendation | § |
|---|---|---|---|
| Kotlin plugin | not applied in convention plugins (built-in Kotlin) | correct | 2.8 |
| `CommonExtension` generic params | none (AGP 9 form) | correct | 2.8 |
| `compileSdk 37` / `targetSdk 36` / `minSdk 26` | yes | OK. Play requires target ≥ 36 now (`OB:docs/qa/release.md:410-420`) | 2.5 |
| `-Xcontext-receivers` | dropped | correct (Ownbit `KotlinAndroid.kt:80` must not be copied) | 2.8 |
| `vectorDrawables.useSupportLibrary = true` | kept | remove (irrelevant at minSdk 26) | 2.8 |
| `localeFilters` (AGP 9 name of `resourceConfigurations`) | yes | keep; align list with the written dirs | 8.6 |
| `noCompress += js, pack` | yes | keep | 3.7 |
| Excluding QuickJS test assets | `packaging.resources.excludes` | does not affect assets → `androidResources.ignoreAssetsPattern` | 3.7 |
| R8 rules | QuickJS keep, serializer `Companion` keep, Log strip | add `INSTANCE` + `@HiltViewModel` rules, `SourceFile,LineNumberTable` | 5 |
| `pre*ReleaseBuild` doFirst | captures script vals | rewrite to be configuration-cache safe | 3.9 |
| Permission allowlist check | `tools:node="remove"` for INTERNET / ACCESS_NETWORK_STATE only | add a merged-manifest allowlist task per variant | 7.3 |
| Engine digest guard | none | unit test pinned to `6933d0d8…1077` / 1,181,026 bytes + `.gitattributes` | 7.5 |
| Importer | slug names, `%@→%s`, `%lld→%d`, ` ` escaping | add persisted names, Unicode-whitespace quoting, java.util.Formatter test, drop `values-pt` = pt-PT | 8.5 |
| `androidx.biometric` in catalog | yes | decision owned by `app-shell.md` / `activity-settings.md` §3.1. Ownbit has none (§10.5) | 10 |

---

## 2. Gradle layout to replicate

### 2.1 `settings.gradle.kts`

Ownbit (`OB:settings.gradle.kts:1-53`): `pluginManagement { includeBuild("build-logic"); repositories { google();
mavenCentral(); gradlePluginPortal() } }`, `dependencyResolutionManagement { repositoriesMode =
FAIL_ON_PROJECT_REPOS; google(); mavenCentral(); jitpack (content-filtered to com.github.* and Scarlet) }`,
`rootProject.name = "ownbit"`, `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")` (so `:app` writes
`implementation(projects.core.l10n)`, `OB:app/build.gradle.kts:303-338`), then 17 `:core:*` and 17 `:feature:*`
modules.

Signer (`SIG:settings.gradle.kts`): same shape. It has **no jitpack**: keep only `google()` and `mavenCentral()`.
Every dependency must come from those two, which keeps the supply chain auditable for an offline signer. Modules
`:app`, `:core:{common, model, l10n, designsystem, engine, crypto, data, security, scanner, ui, navigation}`,
`:feature:{onboarding, setup, wallets, sign, activity, settings}`.

Ownbit module rules to keep, quoted from the code:

- "Feature modules must NOT depend on other feature modules; navigate via :core:navigation routes"
  (`OB:build-logic/convention/src/main/kotlin/AndroidFeatureConventionPlugin.kt:8-11`).
- Each module keeps its own reflective code's keep rules in its `consumer-rules.pro`. `app/proguard-rules.pro`
  holds only cross-module rules (`OB:app/proguard-rules.pro:1-12`).
- Module-owned string names use a `<module>__name` prefix (double underscore). Generated iOS names never contain
  `__`, so the two can never collide (`OB:tools/strings-import/strings_import/android.py:56-57`).
- A boundary test enforces "this module never goes online": `PublicSignerBoundaryTest` greps the module's sources
  for `:core:network`, OkHttp/Retrofit, `java.net`, `javax.net`, `android.net`, `android.webkit` and declares the
  sources as test inputs (`OB:core/publicsigner/build.gradle.kts:7-16,95-100`). **The signer should apply this to
  every module** (allow `android.net.Uri` only). A test over the release runtime classpath should also reject
  `com.squareup.okhttp3`, `retrofit2`, `io.ktor`, `com.google.firebase`, `com.google.android.gms`,
  `com.google.mlkit` and `com.google.android.datatransport`. Ownbit's merged manifest shows that ML Kit pulls in
  `datatransport` (CCT backend), i.e. network telemetry
  (`OB:app/build/intermediates/merged_manifests/apkRelease/.../AndroidManifest.xml:249,321-334`). The scaffold
  already uses ZXing instead. Keep it that way.

### 2.2 Root `build.gradle.kts`

Ownbit (`OB:build.gradle.kts`): every plugin `alias(libs.plugins.X) apply false` (android-application,
android-library, kotlin-android, kotlin-jvm, kotlin-serialization, compose-compiler, ksp, hilt). This loads the plugin
jars once into the root classloader. build-logic only `compileOnly`s them (§2.4).

Signer: same list (`SIG:build.gradle.kts`). Under AGP 9 `kotlin-android` is **never applied**. Its `apply false`
entry only puts KGP 2.4.21 on the build classpath, so AGP's built-in Kotlin compiles with 2.4.21 instead of the
KGP version AGP depends on by default. Keep the entry, and keep the comment that says so.

### 2.3 `gradle.properties`

Ownbit (`OB:gradle.properties:1-13`):

```properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=768m -XX:+UseParallelGC -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.workers.max=4
org.gradle.daemon.idletimeout=600000
kotlin.daemon.jvmargs=-Xmx3g -XX:+UseParallelGC
kotlin.code.style=official
android.useAndroidX=true
android.nonTransitiveRClass=true
android.suppressUnsupportedCompileSdk=35          # stale, do not copy
org.gradle.java.home=/Users/…/jbr-21.0.11/…        # do not copy (see below)
```

Lessons:
- **R8 needs about 4 GB of Gradle heap**. With 2 GB `minifyReleaseWithR8` died with "GC overhead limit exceeded"
  (`OB:docs/qa/release.md:56-59`). The signer is much smaller, but keep `-Xmx4g`.
- `org.gradle.java.home` was pinned to a JBR 21 because Android Studio 2026.1 bundles JBR 25, which Gradle 8.11
  cannot run on. The pin then broke new daemons (`OB:docs/qa/release-smoke.md:157-161`). The signer instead uses
  Gradle's daemon JVM criteria (`SIG:gradle/gradle-daemon-jvm.properties`: `toolchainVersion=21`), which is the right
  replacement.
- Signer additions: `org.gradle.configuration-cache=true`, already present. It is Gradle 9's preferred mode and
  requires §3.9.

### 2.4 build-logic (included build)

`OB:build-logic/settings.gradle.kts` reuses the root catalog:

```kotlin
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "build-logic"
include(":convention")
```

`OB:build-logic/gradle.properties`: `org.gradle.parallel=true`, `org.gradle.caching=true`,
`org.gradle.configureondemand=true`. The signer adds `org.gradle.configuration-cache=true`.

`OB:build-logic/convention/build.gradle.kts:1-58`: `kotlin-dsl`, Java/JVM target 17,
`compileOnly(libs.android.gradlePlugin)`, `compileOnly(libs.kotlin.gradlePlugin)`,
`compileOnly(libs.ksp.gradlePlugin)` and `compileOnly(libs.compose.gradlePlugin)`, plus one
`gradlePlugin.plugins.register` per convention:

| Ownbit id | Signer id (`SIG:build-logic/convention/build.gradle.kts`) | Class | What it does |
|---|---|---|---|
| `ownbit.android.application` | `bittool.android.application` | `AndroidApplicationConventionPlugin` | `com.android.application` + compose compiler; `configureKotlinAndroid`, `configureCompose`; `targetSdk`; `buildFeatures.buildConfig = true` (`OB:…/AndroidApplicationConventionPlugin.kt:11-23`) |
| `ownbit.android.library` | `bittool.android.library` | `AndroidLibraryConventionPlugin` | `com.android.library`; `configureKotlinAndroid`; `consumerProguardFiles("consumer-rules.pro")`; coroutines-android + junit / kotlin-test / coroutines-test / androidx test deps (`OB:…/AndroidLibraryConventionPlugin.kt:10-28`) |
| `ownbit.android.compose` | `bittool.android.compose` | `AndroidComposeConventionPlugin` | applies the library plugin + compose compiler, `configureCompose` (`OB:…/AndroidComposeConventionPlugin.kt:9-18`) |
| `ownbit.android.feature` | `bittool.android.feature` | `AndroidFeatureConventionPlugin` | compose + hilt + kotlin-serialization; implementation of `:core:{common, model, designsystem, ui, l10n, navigation, data}` + core-ktx, lifecycle-runtime-compose, lifecycle-viewmodel-compose, navigation-compose, hilt-navigation-compose, serialization-json; test mockk + turbine (`OB:…/AndroidFeatureConventionPlugin.kt:12-35`). The signer also adds `:core:security` and activity-compose |
| `ownbit.hilt` | `bittool.hilt` | `HiltConventionPlugin` | ksp + `com.google.dagger.hilt.android`; `implementation(hilt-android)`, `ksp(hilt-compiler)` (`OB:…/HiltConventionPlugin.kt:8-17`) |
| `ownbit.android.room` | (none: the signer has no database) | `AndroidRoomConventionPlugin` | ksp args `room.schemaLocation`, `room.generateKotlin`, `room.incremental` (`OB:…/AndroidRoomConventionPlugin.kt:10-26`) |
| `ownbit.jvm.library` | `bittool.jvm.library` | `JvmLibraryConventionPlugin` | `org.jetbrains.kotlin.jvm` + `configureKotlinJvm` (`OB:…/JvmLibraryConventionPlugin.kt:8-17`) |

Helpers worth copying verbatim (`OB:build-logic/convention/src/main/kotlin/io/ownbit/buildlogic/ProjectExtensions.kt:10-23`):

```kotlin
val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalStateException("Missing library alias '$alias' in libs.versions.toml") }

fun VersionCatalog.pluginId(alias: String): String =
    findPlugin(alias).orElseThrow { IllegalStateException("Missing plugin alias '$alias'") }.get().pluginId

object OwnbitConfig { const val COMPILE_SDK = 36; const val MIN_SDK = 26; const val TARGET_SDK = 36 }
```

The signer has `SignerConfig { COMPILE_SDK = 37; MIN_SDK = 26; TARGET_SDK = 36 }`
(`SIG:build-logic/convention/src/main/kotlin/com/bittool/buildlogic/ProjectExtensions.kt`). Catalog lookups in
plugins use the dashed alias (`libs.lib("androidx-compose-bom")`). Plugin ids are read from the catalog
(`libs.pluginId("compose-compiler")`) so no id string is duplicated.

`configureKotlinAndroid` (`OB:…/io/ownbit/buildlogic/KotlinAndroid.kt:13-47`): `compileSdk`; `minSdk`;
`testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`; Java 17 source/target; packaging excludes
`/META-INF/{AL2.0,LGPL2.1}`, `/META-INF/INDEX.LIST`, `/META-INF/DEPENDENCIES`,
`/META-INF/versions/9/OSGI-INF/MANIFEST.MF`; `testOptions.unitTests.isIncludeAndroidResources = true` and
`isReturnDefaultValues = true`; `lint { lintConfig = rootProject.file("lint.xml"); abortOnError = true }`. It then
calls `configureKotlin()`, which sets jvmTarget 17 and the opt-ins `kotlin.RequiresOptIn`,
`kotlinx.coroutines.ExperimentalCoroutinesApi` and `kotlinx.coroutines.FlowPreview` (plus `-Xcontext-receivers`,
dropped), and `configureUnitTestJvm()`, which adds the JVM args `-Djdk.attach.allowAttachSelf=true`,
`-XX:+StartAttachListener` and `-XX:+EnableDynamicAgentLoading` so MockK can self-attach on busy machines
(`KotlinAndroid.kt:58-70`).

`configureCompose` (`OB:…/Compose.kt:7-26`): `buildFeatures.compose = true`; BOM as `platform()` for
`implementation` and `androidTestImplementation` (the signer also adds it for `testImplementation`); ui,
ui-graphics, foundation, material3, material-icons-extended, animation, ui-tooling-preview;
`debugImplementation` ui-tooling and ui-test-manifest; `androidTestImplementation` ui-test-junit4.

### 2.5 Version catalog pattern

`OB:gradle/libs.versions.toml` has three sections:
- `[versions]`: one key per family (`agp`, `kotlin`, `ksp`, `composeBom`, `lifecycle`, `hilt`, …).
- `[libraries]`: `group` / `name` / `version.ref`. Build-logic artifacts are listed first under `# build-logic`
  (`android-gradlePlugin`, `kotlin-gradlePlugin`, `ksp-gradlePlugin`, `compose-gradlePlugin` with
  `version.ref = "kotlin"`). Compose artifacts have **no version** because the BOM supplies it.
- `[plugins]`: the external plugins with versions, then the convention plugins **without version**:
  `ownbit-android-application = { id = "ownbit.android.application" }` … so modules write
  `alias(libs.plugins.ownbit.android.feature)`.

Ownbit anti-patterns not to copy:
- Hard-coded coordinates in module scripts: Roborazzi `"io.github.takahirom.roborazzi:roborazzi:1.32.2"` in five
  modules (`OB:app/build.gradle.kts:363-364`, `OB:core/designsystem/build.gradle.kts`, …),
  `"androidx.savedstate:savedstate:1.2.1"` (`OB:core/designsystem/build.gradle.kts`) and
  `"androidx.datastore:datastore:${libs.versions.datastore.get()}"` (`OB:core/crypto/build.gradle.kts`). Put every
  artifact in the catalog.

Signer catalog (`SIG:gradle/libs.versions.toml`), compared with Ownbit:

| Key | Ownbit | Signer |
|---|---|---|
| agp | 8.9.3 | 9.4.1 |
| kotlin (also compose-compiler + serialization plugins) | 2.1.0 | 2.4.21 |
| ksp | 2.1.0-1.0.29 (KSP1 scheme) | 2.3.12 (KSP2 scheme, independent of the Kotlin version) |
| composeBom | 2024.12.01 | 2026.09.00 |
| hilt | 2.54 | 2.60.1 |
| Gradle wrapper | 8.11.1 | 9.7.1 |
| camera / QR | CameraX 1.4.1 + ML Kit 17.3.0 + ZXing 3.5.3 | CameraX 1.6.2 + ZXing 3.5.4 (no ML Kit, see §2.1) |
| JS engine | androidx.webkit (WebView) | `wang.harlon.quickjs:wrapper-android:3.2.3` |

Check after the BOM bump: `androidx.compose.material:material-icons-extended` stopped evolving at 1.7.x. Confirm
that BOM 2026.09.00 still constrains it. If it does not, pin the version in the catalog or (preferred) ship the
needed Material Symbols as vector drawables. That also removes a very large dependency.

### 2.6 Module build-script conventions (from Ownbit)

- One `namespace` per module (`io.ownbit.core.l10n`, …). For the signer: `com.bittool.signer.core.<name>` /
  `com.bittool.signer.feature.<name>`, unique per module (AGP 9 enforces unique namespaces).
- Library `BuildConfig` is off by default. Turn it on per module only when the module reads it (Ownbit
  `:core:common`, `OB:core/common/build.gradle.kts`).
- Screenshot tests (Roborazzi on Robolectric) only record with a property, otherwise they are excluded
  (`OB:app/build.gradle.kts:219-232`, the same block in each feature):

  ```kotlin
  testOptions.unitTests.all { test ->
      val record = project.findProperty("ownbit.screenshots")?.toString() == "true"
      test.systemProperty("roborazzi.test.record", record.toString())
      test.maxHeapSize = "1536m"
      if (!record) test.exclude("**/screenshot/**")
  }
  ```
  Signer: put this in the library/application convention plugin with the property `signer.screenshots`, not in
  every module.
- Legacy task aliases so that pre-flavor commands keep working (`OB:app/build.gradle.kts:283-301`):
  `testDebugUnitTest → testApkDebugUnitTest + testPlayDebugUnitTest`, `testReleaseUnitTest → …`,
  `installDebug → installApkDebug`, `lintDebug → lintApkDebug + lintPlayDebug`, `lintRelease → …`. Registered only
  `if (tasks.findByName(alias) == null)` in group `ownbit` ("bittool" for the signer).

### 2.7 Lint policy

`OB:lint.xml:6-28` is wired into every module by `configureKotlinAndroid` (`lintConfig`, `abortOnError = true`).
`:app` sets `lint { checkDependencies = true }` (`OB:app/build.gradle.kts:184-187`), so `:app:lintRelease` reports
all modules at once. Every issue keeps its default severity. Ownbit ignores exactly three:
- `MissingTranslation`: English is the fallback; gaps are tracked by the importer report. `:core:l10n` disables it
  too (`OB:core/l10n/build.gradle.kts:9-16`). Keep both settings in sync, otherwise lint reports
  `CannotEnableHidden` (`OB:docs/qa/release.md:286-291`).
- `LocaleFolder`: `values-b+id` ships on purpose next to `values-in`.
- `NullSafeMutableLiveData`: a lint crash from lifecycle 2.9 against AGP 8.7's lint
  (`OB:docs/qa/release-smoke.md:131`). Not needed with AGP 9.4's lint.

Signer (`SIG:lint.xml`): `LocaleFolder` ignored, `MissingTranslation` warning. The iOS catalogue is complete in all
21 languages, and both the iOS writer (`IOS:scripts/write-localizations.py:27-33`) and the scaffold importer refuse
a missing string. The signer could therefore make `MissingTranslation` an **error** for imported strings once the
Android-only strings (§8.8) are also complete. Generated files should carry
`tools:ignore="PluralsCandidate,Typos,TypographyDashes,TypographyEllipsis"` (Ownbit:
`OB:tools/strings-import/strings_import/pipeline.py`, `content_ignores`): the copy is owned by iOS, and the
ellipsis check in particular would push "..." ↔ "…" changes that break engine lookups (§8.4).

### 2.8 AGP 9 / Kotlin 2.4 / Gradle 9: what changes in the convention plugins

| Ownbit code (AGP 8.9 / Kotlin 2.1) | Signer (AGP 9.4.1, built-in Kotlin) |
|---|---|
| `pluginManager.apply("org.jetbrains.kotlin.android")` in application and library plugins (`AndroidApplicationConventionPlugin.kt:14`, `AndroidLibraryConventionPlugin.kt:13`) | **Remove.** AGP 9 compiles Kotlin itself (`android.builtInKotlin=true` is the default; do not set it to `false`). Applying KGP's Android plugin on top of built-in Kotlin is rejected. `org.jetbrains.kotlin.plugin.compose` and `org.jetbrains.kotlin.plugin.serialization` are still applied (they are compiler plugins, not the Android integration). `kapt` is not available; KSP only |
| `CommonExtension<*, *, *, *, *, *>` (`Compose.kt:7`, `KotlinAndroid.kt:13`) | `com.android.build.api.dsl.CommonExtension` without type parameters (scaffold already does this) |
| `import com.android.build.gradle.LibraryExtension` (`AndroidComposeConventionPlugin.kt:1`, `AndroidLibraryConventionPlugin.kt:1`) | `com.android.build.api.dsl.LibraryExtension`. The old `com.android.build.gradle.*` extension types (`BaseExtension`, `AppExtension`, `LibraryExtension`) are not available with AGP 9's new DSL (`android.newDsl` default `true`). Variant work goes through `androidComponents { onVariants { } }` only; there is no `applicationVariants.all {}` |
| `compileSdk = 36` | `compileSdk = 37` still works. AGP 9 also has the block form `compileSdk { version = release(37) }` (and `minSdk { version = release(26) }`). Use one form consistently. The block form also allows a minor API level |
| `defaultConfig.targetSdk` only in the app plugin | Keep: app only. In libraries set `lint { targetSdk = TARGET_SDK }` and `testOptions { targetSdk = TARGET_SDK }` instead of a library `targetSdk` (deprecated for libraries). Ownbit's security audit recommended targetSdk handling in library modules to stop implied permissions (`OB:docs/qa/security-audit.md:261-262`); the permission allowlist task of §7.3 catches that class of problem regardless |
| `tasks.withType<KotlinCompile>().configureEach { compilerOptions { … } }` (`KotlinAndroid.kt:72-84`) | Still valid with built-in Kotlin (AGP creates KGP `KotlinCompile` tasks), and it is what the scaffold uses. The equivalent extension form is `kotlin { compilerOptions { … } }`. `android { kotlinOptions { } }` is gone. Kotlin sources live in `src/<set>/kotlin` through `android.sourceSets`, not `kotlin.sourceSets` |
| `-Xcontext-receivers` (`KotlinAndroid.kt:80`) | Remove. Kotlin 2.x replaced context receivers with context parameters (`-Xcontext-parameters`), and 2.4 does not accept the old flag |
| `vectorDrawables { useSupportLibrary = true }` (`KotlinAndroid.kt:19`) | Remove (only matters below API 21) |
| `resourceConfigurations += listOf(…)` (`app/build.gradle.kts:117-120`) | `androidResources { localeFilters += listOf(…) }` (scaffold already uses it) |
| `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), …)` | Same. Only the `-optimize` default file is supported |
| `variant.signingConfig?.setConfig(…)` (`app/build.gradle.kts:245`) | `variant.signingConfig.setConfig(…)` (the property is non-null for application variants; scaffold already uses it) |
| KSP `2.1.0-1.0.29` | KSP 2.3.x (KSP2, which supports built-in Kotlin). Hilt ≥ 2.57 uses the new AGP APIs; 2.60.1 is pinned. Run one full `assembleRelease` after any bump |
| Consumer keep rules may contain global options (Reown shipped `-allowaccessmodification`, `-keeppackagenames`, `OB:docs/qa/release.md:195-199`) | AGP 9 refuses global options in library consumer rules. Keep module `consumer-rules.pro` to `-keep*` / `-dontwarn` / `-assumenosideeffects` only. QuickJS's `proguard.txt` contains only `-keepclasseswithmembers class * { native <methods>; }` and `-keep class com.whl.quickjs.**{*;}`, so it is fine |
| `org.gradle.java.home` pin | Daemon JVM criteria file (`gradle/gradle-daemon-jvm.properties`, `toolchainVersion=21`) |
| No configuration cache | Configuration cache on: script-object captures in task actions fail (§3.9). Read secrets through `providers.gradleProperty` / `providers.environmentVariable` / file reads at configuration time (all tracked as inputs) |

Recommended signer `configureKotlinAndroid` (AGP 9), compared with the scaffold:

```kotlin
internal fun Project.configureKotlinAndroid(common: CommonExtension) {
    common.apply {
        compileSdk = SignerConfig.COMPILE_SDK
        defaultConfig.apply {
            minSdk = SignerConfig.MIN_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        packaging.resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF", "DebugProbesKt.bin",
        )
        testOptions.unitTests.isIncludeAndroidResources = true
        testOptions.unitTests.isReturnDefaultValues = true
        lint.lintConfig = rootProject.file("lint.xml")
        lint.abortOnError = true
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.coroutines.FlowPreview",
            )
        }
    }
    tasks.withType<Test>().configureEach {
        jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+StartAttachListener", "-XX:+EnableDynamicAgentLoading")
        maxHeapSize = "1536m"
    }
}
```

---

## 3. `:app` build script anatomy (Ownbit), with signer deltas

### 3.1 Secrets helper

`OB:app/build.gradle.kts:9-13`:

```kotlin
val secrets = Properties().apply { val f = rootProject.file("local.properties"); if (f.exists()) f.inputStream().use { load(it) } }
fun secret(key: String): String = (secrets.getProperty(key) ?: System.getenv(key.uppercase().replace('.', '_')) ?: "")
```

`ownbit.release.storeFile` maps to the environment variable `OWNBIT_RELEASE_STOREFILE` (the whole key is
upper-cased). The signer copies this with the `signer.` prefix (`SIG:app/build.gradle.kts`): `signer.release.*`,
`signer.upload.*`, `SIGNER_RELEASE_STOREFILE`, …. Ownbit's server secrets (`ownbit.hashPostfix`,
`ownbit.encryptPrefix`, `ownbit.wcProjectId`, `FIREBASE_*`, test server, `OB:app/build.gradle.kts:15-27,121-130`) have
**no signer equivalent**. The signer has no secrets at all, so it needs no `missingReleaseSecrets` check
(`OB:app/build.gradle.kts:240-241`).

### 3.2 Version scheme

`OB:app/build.gradle.kts:29-41` and `OB:docs/qa/release.md:270-280,419-420`:

```kotlin
val ownbitVersionName = "5.1.1"
val ownbitBuildNumber = providers.gradleProperty("ownbit.buildNumber").orNull?.trim()?.toInt() ?: 0
val ownbitVersionCode: Int = ownbitVersionName.split('.').map(String::toInt).let { (major, minor, patch) ->
    require(minor in 0..99 && patch in 0..99 && ownbitBuildNumber in 0..99) { "… out of range (0–99 each)" }
    major * 1_000_000 + minor * 10_000 + patch * 100 + ownbitBuildNumber
}
```

- 5.0.0 → 5000000, 5.0.1 → 5000100, 5.1.0 → 5010000. `BUILD` (`-Pownbit.buildNumber=N`) re-issues the same
  versionName, for example after a rejected store upload. "A Play upload never reuses a versionCode, also when the
  earlier upload was not released" (`release.md:419-420`).
- The same versionCode is used for both flavors. Versions must be dotted numbers.
- Debug adds `versionNameSuffix = "-debug"` and `applicationIdSuffix = ".debug"` (`OB:app/build.gradle.kts:157-159`).
- Signer: already identical (`signer.buildNumber`, versionName `1.0.0` → **1000000**), kept in step with the iOS
  `MARKETING_VERSION = 1.0.0` (`IOS:BitToolSigner.xcodeproj/project.pbxproj:441`). The iOS build number is
  `CURRENT_PROJECT_VERSION = 1` (`:414`), and iOS shows the version as `"\(short) (\(build))"`, i.e. "1.0.0 (1)"
  (`IOS:BitToolSigner/Features/Settings/SettingsTab.swift:13-18`, also `:231-236`). On Android, show
  `"$versionName ($versionCode)"` ("1.0.0 (1000000)") so the value matches what `aapt2 dump badging` and the release
  manifest report. This is a deliberate, documented deviation; alternatively show `BUILD` only. Owner to decide.

### 3.3 Signing (release key and optional Play upload key)

`OB:app/build.gradle.kts:43-67,84-101,169-181,243-247`, documented in `OB:docs/qa/release.md:226-268`:

- `ownbit.release.{storeFile, storePassword, keyAlias, keyPassword}`: all four present → the release `signingConfig`.
  The `storeFile` path is relative to the repo root or absolute.
- None present → **unsigned** output (`app-…-release-unsigned.apk`, unsigned `.aab`).
- `-Pownbit.allowDebugSigning=true` → signed with the debug key, "for local device testing only — never distribute".
- Some but not all present → `preReleaseBuild` **fails** and names the missing keys.
- `ownbit.upload.*` (env `OWNBIT_UPLOAD_*`), all four set → **only the `play` release** is signed with it, through
  `androidComponents.onVariants(selector().withBuildType("release").withFlavor("distribution" to "play")) {
  variant.signingConfig?.setConfig(android.signingConfigs.getByName("upload")) }`. The `apk` flavor keeps the
  release key.
- The minSdk is 26, so APK Signature Scheme v2 (+v3) is used and no v1 is needed.
- The signer scaffold already copies all of this. The key decision is in §7.4.

### 3.4 `defaultConfig`

`OB:app/build.gradle.kts:103-135`:
- `applicationId = "io.ownbit.app" + -Pownbit.appIdSuffix` (a second side-by-side install for owner testing) and
  `manifestPlaceholders["appLabel"] = -Pownbit.appLabel ?: "@string/app_name"`, read by the manifest's
  `android:label="${appLabel}"` (`OB:app/src/main/AndroidManifest.xml:27`). The signer can skip the suffix trick;
  the debug build already has `.debug`. Recommended: a debug-only `app_name` override in
  `app/src/debug/res/values/strings.xml` ("BitTool Signer Debug", `translatable="false"`), so the two launcher icons
  can be told apart without `resValue` (AGP 9 has `buildFeatures.resValues` off by default).
- `resourceConfigurations` / `localeFilters`: see §8.6.
- `ndk { abiFilters += releaseAbis }` with `releaseAbis = listOf("arm64-v8a", "armeabi-v7a")`; debug adds
  `x86_64` (`OB:app/build.gradle.kts:69-70,132-134,160-162`). Ownbit excluded x86_64 from release only because
  Reown's yttrium has no x86 library. **Signer:** QuickJS ships arm64-v8a, armeabi-v7a, x86 and x86_64
  (1,327,232 / 946,424 / 1,357,880 / 1,400,768 bytes, all already stripped). Recommended release filters:
  `arm64-v8a, armeabi-v7a, x86_64`. armeabi-v7a matters: old 32-bit phones are typical air-gapped devices.
  x86_64 avoids lint `ChromeOsAbiSupport`, and Play's ABI split means phones never download it.
- `ndkVersion = "27.0.12077973"` (`OB:app/build.gradle.kts:75-82`) existed only so AGP could strip Reown's 150 MB
  `.so`. **Not needed for the signer**, because the QuickJS libraries are already stripped. AGP will log "Unable to
  strip library … missing strip tool"; silence it with
  `packaging.jniLibs.keepDebugSymbols += "**/libquickjs-android-wrapper.so"`, or ignore it.

### 3.5 Product flavors

`OB:app/build.gradle.kts:137-154`:

```kotlin
flavorDimensions += "distribution"
productFlavors {
    create("apk")  { dimension = "distribution"; isDefault = true; buildConfigField("String", "DISTRIBUTION", "\"apk\"") }
    create("play") { dimension = "distribution";                  buildConfigField("String", "DISTRIBUTION", "\"play\"") }
}
```

"`apk` is the default variant (Android Studio, `apkDebug` for QA / emulator scripts). Same applicationId for both."
Flavors exist **only in `:app`**. Library modules are flavorless and receive the distribution through DI (§4.2).
The signer scaffold already has this block.

### 3.6 Build types

`OB:app/build.gradle.kts:156-182`: debug as above; release `isMinifyEnabled = true`, `isShrinkResources = true`,
`proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`, and the signing
fallback chain `hasReleaseKey → release; allowDebugSigning → debug; else null`. R8 full mode is the default and
must not be turned off (`OB:docs/qa/release.md:65`).

### 3.7 `androidResources`, `bundle`, `packaging`

Ownbit:
- `androidResources { generateLocaleConfig = false }` with a hand-written `res/xml/locales_config.xml`
  (`OB:app/build.gradle.kts:189-191`).
- `bundle { language { enableSplit = false }; abi { enableSplit = true }; density { enableSplit = true } }`
  (`:193-199`): "In-app language switching needs every language in the base APK (lint AppBundleLocaleChanges)".
- `packaging { jniLibs { useLegacyPackaging = false } }` (`:201-204`): uncompressed, page-aligned `.so` files
  loaded straight from the APK. Ownbit also has Java-resource excludes (`junit/runner/*.gif`, `LICENSE-junit.txt`,
  `DebugProbesKt.bin`, BouncyCastle Picnic tables) that are specific to its dependencies.

Signer deltas:
- `androidResources.noCompress += listOf("js", "pack")` (scaffold): lets `assets/engine/signer.js` (1,181,026 B)
  and `TokenIcons.pack` (11,205,605 B) be opened with `AssetManager.openFd` or memory-mapped. It does not change
  the bytes a user extracts with `unzip`.
- **QuickJS test assets.** The AAR ships `assets/test_base_module1.mjs`, `test_base_module2.mjs`,
  `test_assert_define.js`, `test_polyfill_date.js` and `test_module_import_dynamic.js`. They are merged into the
  APK's `assets/`. `packaging.resources.excludes` (scaffold) applies to **Java resources**, not Android assets, so
  it does not remove them. Use the asset ignore pattern, which `mergeAssets` honours for dependencies too. Setting
  it **replaces** aapt's default pattern, so repeat the default:

  ```kotlin
  androidResources {
      // aapt default (keep): !.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~
      ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!test_*.js:!test_*.mjs"
  }
  ```
  Verify with `unzip -l app-apk-release.apk 'assets/*'`. Only the signer's own assets may be listed (§7.2 step 6).
  Note the default `<dir>_*` rule: never put assets in a directory whose name starts with `_`.

### 3.8 `testOptions`, `lint`, dependencies

As in §2.6 / §2.7. Ownbit `:app` dependencies (`OB:app/build.gradle.kts:303-367`) list every core and feature
module explicitly through type-safe accessors, plus core-ktx, appcompat, activity-compose, core-splashscreen,
lifecycle runtime-compose / viewmodel-compose / process, navigation-compose, hilt-navigation-compose and
serialization-json. The signer `:app` (`SIG:app/build.gradle.kts`) matches. It must not gain Firebase or any
network library.

### 3.9 Release sanity checks (rewrite for configuration cache)

Ownbit (`OB:app/build.gradle.kts:249-281`):
`tasks.matching { it.name.matches(Regex("pre(Apk|Play)ReleaseBuild")) }.configureEach { doFirst { … } }`.
It warns when the NDK is missing, fails on half-configured release or upload keys and on missing server secrets, and
logs one of:
- "Ownbit release: play signed with the Play upload key (ownbit.upload.keyAlias)."
- "Ownbit release: signed with the release key (ownbit.release.keyAlias)."
- "Ownbit release: signed with the DEBUG key (-Pownbit.allowDebugSigning) — for local testing only."
- "Ownbit release: UNSIGNED (no ownbit.release.* keys). Sign before distributing; see docs/qa/release.md."

The `doFirst` lambda reads script-level values (`hasReleaseKey`, `missingReleaseKeys`, …), so it captures the
script object, which the configuration cache cannot store. The signer scaffold copied this pattern while enabling
the cache. Fix: copy plain values into locals inside `configureEach` before `doFirst`:

```kotlin
tasks.matching { it.name.matches(Regex("pre(Apk|Play)ReleaseBuild")) }.configureEach {
    val isPlay = name.contains("Play")
    val partialRelease = (!hasReleaseKey && missingReleaseKeys.size < releaseSigningKeys.size)
        .let { if (it) missingReleaseKeys.joinToString { k -> "signer.release.$k" } else null }
    val partialUpload = (!hasUploadKey && missingUploadKeys.size < uploadSigningKeys.size)
        .let { if (it) missingUploadKeys.joinToString { k -> "signer.upload.$k" } else null }
    val mode = when {
        hasUploadKey && isPlay -> "play signed with the Play upload key."
        hasReleaseKey -> "signed with the release key."
        allowDebugSigning -> "signed with the DEBUG key (-Psigner.allowDebugSigning) — local testing only."
        else -> "UNSIGNED (no signer.release.* keys). Sign before distributing."
    }
    doFirst {
        partialRelease?.let { throw GradleException("Release signing is only partially configured; missing $it (local.properties or environment).") }
        partialUpload?.let { throw GradleException("Play upload signing is only partially configured; missing $it (local.properties or environment).") }
        logger.lifecycle("BitTool Signer release: $mode")
    }
}
```

Run `./gradlew :app:assembleApkRelease --configuration-cache` twice; the second run must report "Configuration cache
entry reused".

---

## 4. Ownbit's `apk` vs `play`: every difference

### 4.1 Difference table

| Aspect | `apk` | `play` | Source |
|---|---|---|---|
| `BuildConfig.DISTRIBUTION` | `"apk"` | `"play"` | `OB:app/build.gradle.kts:146-153` |
| Default variant | yes (`isDefault = true`) | no | `:147` |
| Manifest | `src/apk/AndroidManifest.xml` adds `REQUEST_INSTALL_PACKAGES` and `io.ownbit.app.update.UpdateFileProvider` (`${applicationId}.updates`, `exported=false`, `grantUriPermissions=true`, paths `cache-path updates/`) | nothing extra | `OB:app/src/apk/AndroidManifest.xml:1-23`, `OB:app/src/apk/res/xml/update_file_paths.xml` |
| Code (flavor source set) | `src/apk/kotlin/.../di/DistributionModule.kt` binds `AppUpdateService ← ApkUpdateService`, `ApkPlatform ← AndroidApkPlatform`, `StartupNoticeSource ← ApkUpdateNotices`; plus `update/ApkDownloader.kt`, `ApkPlatform.kt`, `ApkUpdateNotices.kt`, `ApkUpdateService.kt` (551 lines) | `src/play/kotlin/.../di/DistributionModule.kt` binds only `StartupNoticeSource ← NoStartupNotices`. **No** `AppUpdateService` binding, so the `Optional` stays empty and no update code is reachable | `OB:app/src/apk/kotlin/io/ownbit/app/di/DistributionModule.kt:14-30`, `OB:app/src/play/kotlin/io/ownbit/app/di/DistributionModule.kt:10-21` |
| Optional binding | `:core:data` declares `@BindsOptionalOf AppUpdateService` | same, unbound | `OB:core/data/src/main/kotlin/io/ownbit/core/data/di/DataUpdateModule.kt` |
| About screen | "Check for updates" row (current version subtitle; the new version in brand blue + "Update" chip) | "Rate on Google Play" row (`market://details?id=…`, falling back to `https://play.google.com/store/apps/details?id=…`) | `OB:feature/settings/.../about/AboutScreen.kt:101-117,139-142,266-370`, `OB:feature/settings/.../common/ExternalLinks.kt:26-29` |
| Unit tests | `src/testApk/kotlin/.../update/*Test.kt` (3 classes) | shared `src/test` only | `OB:app/src/testApk/` |
| Resources | none | none | — |
| Signing | release key | upload key when `ownbit.upload.*` is set, else the release key | §3.3 |
| R8 / proguard | identical | identical | — |
| Artifact shipped | `.apk` (`:app:assembleApkRelease`, `app/build/outputs/apk/apk/release/app-apk-release[-unsigned].apk`, website) | `.aab` (`:app:bundlePlayRelease`, `app/build/outputs/bundle/playRelease/app-play-release.aab`) | `OB:docs/qa/release.md:20-33` |
| Bundle splits | n/a (universal APK) | language off, ABI on, density on | §3.7 |
| applicationId / versionCode | same | same | `OB:docs/qa/release.md:23` |
| Device identity | ANDROID_ID is per **app signing key**; with Play's Google-generated key the `play` install has a different `equipment` id on the same phone | — | `OB:docs/qa/release.md:260-264` |

Check per distribution (`OB:docs/qa/release.md:50-52`): `aapt2 dump permissions <apk>`. The `apk` build lists
`REQUEST_INSTALL_PACKAGES` and the `UpdateFileProvider`. The `play` build has neither and contains no
`io.ownbit.app.update` classes.

### 4.2 Every Kotlin use of `BuildConfig.DISTRIBUTION` (grep of the whole repo)

1. `OB:app/src/main/kotlin/io/ownbit/app/di/AppConfigModule.kt:28`:
   `distribution = AppDistribution.fromBuildConfig(BuildConfig.DISTRIBUTION)`. This is the only read.
2. `OB:core/common/src/main/kotlin/io/ownbit/core/common/config/AppConfig.kt:23-46`:
   `AppConfig.distribution: AppDistribution = AppDistribution.PLAY` (defaults to the build with no update code),
   `hasInAppUpdates = distribution == APK`, and
   `enum AppDistribution { PLAY, APK; fromBuildConfig(value) = if (value.equals("apk", ignoreCase = true)) APK else PLAY }`.
3. Consumers: `AboutViewModel` (`OB:feature/settings/.../about/AboutScreen.kt:139-142`):
   `updater = updateService.orElse(null)?.takeIf { appConfig.hasInAppUpdates }`, and
   `distribution = if (updater != null) APK else PLAY`. So UI visibility is derived from **the DI binding and the
   flag together**. Tests construct `AppConfig(distribution = …)` (`AboutViewModelTest.kt:50-100`,
   `SettingsScreenshotTest.kt:466-494`, `SettingsI18nRtlScreenshotTest.kt:554,721`).

Pattern to copy: read `BuildConfig` once in `:app`, expose a plain data class from `:core:common`, and bind
flavor-only features in `src/<flavor>/kotlin/.../di/DistributionModule.kt` as `Optional<T>` (`@BindsOptionalOf`).

---

## 5. R8 rules: Ownbit inventory and signer rules

Principle (`OB:docs/qa/release.md:163-166`): "A rule exists only where something is looked up by name at runtime:
reflection, JNI, JNA, name-keyed maps, or getIdentifier." Library rules come from the libraries themselves, each
module's reflective code gets its own `consumer-rules.pro`, and there is no blanket `-dontwarn`
(`OB:app/proguard-rules.pro:1-12`, `release.md:186-199`).

`OB:app/proguard-rules.pro` (all of it):

```proguard
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Hilt keys the ViewModel map by class name; AGP does not read META-INF/proguard of library subprojects.
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.lifecycle.HiltViewModel class *
# Type-safe navigation: an `object` route registered but never navigated lost INSTANCE + serializer() in R8 full mode
# → release-only crash "Serializer for class 'a' is not found" (OB:docs/qa/release-smoke.md:132).
-keepclassmembers @kotlinx.serialization.Serializable class io.ownbit.** {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); public static int i(...); }
```

Our own `Log` facade is a no-op in release (`BuildConfig.DEBUG`). `w`/`e` from third parties stay.

Signer `app/proguard-rules.pro`: keep the scaffold rules (QuickJS keep, serializer `Companion`, Log strip) and
**add** the following:

```proguard
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.lifecycle.HiltViewModel class *
-keepclassmembers @kotlinx.serialization.Serializable class com.bittool.signer.** {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
```

The QuickJS rule `-keep class com.whl.quickjs.** { *; }` duplicates the AAR's own `proguard.txt`; it is harmless
but can be removed. Resource shrinking: every string must be referenced through `R.string` (the generated
`IosStrings` map counts). If anything ever resolves a resource by name (`getIdentifier`), keep those names with a
`res/raw/keep.xml` `tools:keep` list (Ownbit: `feature/bittool/src/main/res/raw/bittool_keep_strings.xml`,
`release.md:201-208`). Archive `app/build/outputs/mapping/{apkRelease,playRelease}/mapping.txt` with every release
and upload the `play` one to Play Console (`release.md:60-62`).

---

## 6. Ownbit's release documents (what exists)

- `OB:docs/qa/release.md` §0 commands, §1 NDK/ABIs, §2 changed files, §3 R8 inventory, §4 signing, §5 version, §6
  lint, §7 risks, §8 device verification, §9 16 KB fix and target API 36.
- `OB:docs/qa/release-smoke.md`: release APK on an emulator, logcat scanned for `ClassNotFound / NoSuchMethod /
  NoSuchField / SerializationException / UnsatisfiedLink / HiltViewModelMap / call adapter /
  IncompatibleClassChange / VerifyError`, stacks retraced with R8 `retrace` + `mapping.txt`. Bugs found that way:
  the lint crash, the `object`-route crash and a WalletConnect resume bug (`:127-133`). Cold start: 613 ms median
  (adb install), 410 ms after `cmd package compile -m speed-profile -f`; debug 2,237 ms (`:93-102`).
- `OB:docs/qa/security-audit.md` §2.6: merged-manifest review (permissions, exported components, FileProviders,
  backup rules, PendingIntent flags), §3 table of secret screens with FLAG_SECURE / keyboard.
- `OB:docs/qa/i18n.md`: string QA, locale screenshot variants (`@Config(qualifiers = "+b+zh+Hans")`, `+ja`, `+th`,
  `+ru`) and translation issues found.
- `OB:tools/check_16kb.py <apk|aar>`: prints `OK`/`BAD`, the minimum LOAD `p_align` and the name of every
  `arm64` `.so` (ELF64 program headers, `p_type == 1`). OK means ≥ 16384.

Ownbit commands (`release.md:25-44`):

```bash
JVM='-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC -Dfile.encoding=UTF-8'
./gradlew :app:assembleApkRelease "$JVM"      # app/build/outputs/apk/apk/release/app-apk-release[-unsigned].apk
./gradlew :app:bundlePlayRelease  "$JVM"      # app/build/outputs/bundle/playRelease/app-play-release.aab
./gradlew :app:lintApkRelease :app:lintPlayRelease "$JVM"
./gradlew :app:assembleApkRelease -Pownbit.allowDebugSigning=true "$JVM"   # local device test only
./gradlew :app:bundlePlayRelease -Pownbit.buildNumber=1 "$JVM"             # re-issue same versionName
./gradlew :app:testApkDebugUnitTest :app:testPlayDebugUnitTest
```

Play lessons: (a) "target API level must be at least 36" was refused at upload, which forced AGP 8.7.3 → 8.9.3
(`release.md:408-417`). (b) A key that already signed distributed APKs cannot become the upload key once Play
generated the app signing key (`:253-259`). (c) 16 KB page size became a Play requirement
(`:311-323,394-401`).

---

## 7. Signer release process (recommended, both flavors)

### 7.1 Commands

```bash
./gradlew :core:engine:testDebugUnitTest :core:l10n:testDebugUnitTest            # engine digest + strings (§7.5, §8.7)
python3 tools/l10n/import_ios_strings.py --check                                 # strings up to date (§8.5)
./gradlew :app:assembleApkRelease :app:bundlePlayRelease                          # + per-variant permission check (§7.3)
./gradlew :app:lintApkRelease :app:lintPlayRelease
./gradlew :app:distApkRelease                                                     # build/dist: renamed APK + .sha256 (§7.6)
```

### 7.2 Checklist (each step must pass; record the output in `release/`)

1. Clean tree, tag `android-vX.Y.Z`. Bump `signerVersionName`. For a re-upload of the same version, use
   `-Psigner.buildNumber=N`.
2. `preApkReleaseBuild` / `prePlayReleaseBuild` print the signing mode. A release is never UNSIGNED or DEBUG.
3. `aapt2 dump badging app-apk-release.apk | head -3` shows `versionCode='1000000' versionName='1.0.0'`,
   `targetSdkVersion:'36'` and the expected `native-code:`.
4. `aapt2 dump permissions` for both the APK and the **universal APK built from the AAB**
   (`bundletool build-apks --mode=universal --bundle app-play-release.aab --output u.apks`) lists only the allowlist
   (§7.3). No `INTERNET`, no `ACCESS_NETWORK_STATE`, no `REQUEST_INSTALL_PACKAGES`.
5. `apksigner verify --print-certs --verbose app-apk-release.apk`: verified with v2 + v3, and "Signer #1 certificate
   SHA-256 digest" equals the published fingerprint. For `play`, compare the Play Console → App integrity → app
   signing certificate SHA-256 with the same fingerprint (§7.4).
6. `unzip -l app-apk-release.apk 'assets/*'` lists only the signer's assets: `engine/signer.js`,
   `token-registry.json`, `known-contracts.json`, `TokenIcons.pack`, `TokenIcons.index.json`, `bip39/*.txt`, the
   legal texts and `THIRD_PARTY_LICENSES*.txt`. No `test_*.js` (§3.7).
7. `unzip -p app-apk-release.apk assets/engine/signer.js | shasum -a 256` gives
   `6933d0d8a6f60faa8222506ac0bd7eef80eb884c9f73e31e9f354b4740f21077` (= `IOS:engine/bundle-manifest.json` `sha256`,
   `bytes` 1181026, and `IOS:release/release-manifest.json` `engineBundle.shippedSHA256`). Do the same on the
   universal APK from the AAB. Note the asset path is `assets/engine/signer.js` in this repo (the scaffold layout),
   not `assets/signer.js` as `activity-settings.md` §9 assumes. Keep the two documents consistent.
8. `python3 tools/check_16kb.py <apk>` (copy Ownbit's script): every arm64 `.so` OK. Also
   `zipalign -c -P 16 -v 4 <apk>`.
9. R8 smoke on a device with the release build (`-Psigner.allowDebugSigning=true` copy): run every flow once in
   airplane mode, then grep logcat for the patterns of §6 (`release-smoke.md`). Retrace with `mapping.txt`.
10. Archive: both `mapping.txt` files, the APK, the AAB, `SHA256SUMS`, and a release manifest. Extend the iOS
    `scripts/release-manifest.sh` idea (`IOS:scripts/release-manifest.sh:1-78`) with `apkSha256`,
    `signingCertSha256`, `engineSha256` (taken from the APK), versionName/versionCode, commit, and the hash of every
    tracked file.
11. Play Console (`play` only): upload the AAB and the mapping file. Data safety: no data collected or shared.
    Permissions: camera only. Privacy policy URL (`https://bittool.com/signer`, the only URL the legal texts may
    contain, `activity-settings.md` §10). Complete the financial-features declaration for a non-custodial
    cryptocurrency wallet; re-read the current Play "Cryptocurrency exchanges and software wallets" policy before
    each release. Play will warn about missing native debug symbols, because QuickJS ships stripped libraries; this
    is expected.
12. Device self-test adapted from `IOS:release/Device-Self-Test.md` (vault without screen lock, reinstall,
    no backup, screenshots and recording, recents thumbnail, App Lock 119 s / 120 s boundary
    (`IOS:docs/Release-1.0.0.md:44-45`, `IOS:BitToolSigner/Services/AppAccessController.swift:99,182-187`), signing
    password cleared on background, camera denial and recovery, 21 languages with Arabic RTL, light and dark,
    largest font).

### 7.3 Merged-manifest permission allowlist (new; both flavors, every variant)

The no-network guarantee is "the OS refuses every socket", so it must be enforced by the build, not just by
`tools:node="remove"` (`SIG:app/src/main/AndroidManifest.xml`). A library added later could bring a permission
nobody removes. Put this in the application convention plugin:

```kotlin
abstract class CheckManifestPermissions : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val mergedManifest: RegularFileProperty
    @get:Input abstract val allowed: SetProperty<String>
    @get:OutputFile abstract val report: RegularFileProperty
    @TaskAction fun run() {
        val xml = mergedManifest.get().asFile.readText()
        val used = Regex("""<uses-permission(?:-sdk-23)?\b[^>]*android:name="([^"]+)"""").findAll(xml)
            .map { it.groupValues[1] }.toSortedSet()
        report.get().asFile.writeText(used.joinToString("\n", postfix = "\n"))
        val extra = used - allowed.get()
        if (extra.isNotEmpty()) throw GradleException("Permissions not allowed in BitTool Signer: $extra")
    }
}

androidComponents.onVariants { variant ->
    val cap = variant.name.replaceFirstChar(Char::uppercase)
    val check = tasks.register<CheckManifestPermissions>("check${cap}Permissions") {
        mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        allowed.addAll("android.permission.CAMERA")
        // androidx.core adds this signature permission for ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED) below API 33:
        allowed.add(variant.applicationId.map { "$it.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" })
        // only if App Lock uses androidx.biometric: "android.permission.USE_BIOMETRIC", "android.permission.USE_FINGERPRINT"
        report.set(layout.buildDirectory.file("reports/permissions/${variant.name}.txt"))
    }
    tasks.matching { it.name == "assemble$cap" || it.name == "bundle$cap" }.configureEach { dependsOn(check) }
}
```

Ownbit's merged release manifest is the counter-example. It has `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`,
`c2dm.RECEIVE` (FCM), ML Kit's and datatransport's components, and androidx's
`io.ownbit.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
(`OB:app/build/intermediates/merged_manifests/apkRelease/processApkReleaseManifest/AndroidManifest.xml:16-51,249-334`).

### 7.4 Signing-key decision (must be made before the first Play upload)

Options:

| Option | Effect |
|---|---|
| A. **Recommended.** One app signing key for both channels: enroll Play App Signing with "export and upload a key from Java keystore" (PEPK) using the key that signs `apk` releases; sign Play uploads with a separate upload key (`signer.upload.*`) | One certificate SHA-256 to publish and show in-app. `apk` ↔ `play` installs update each other (same applicationId `com.bittool.signer`, same certificate; versionCode must not decrease). Google holds the key (Play App Signing is mandatory for AABs) |
| B. Google-generated app signing key (what happened to Ownbit) | Two fingerprints. Switching channels needs an uninstall, which **deletes every wallet's encrypted vault and its Keystore keys**. Acceptable only with a separate applicationId per channel and a loud warning |

The choice cannot be undone after the first Play release (only key rotation). Record it in
`docs/release.md`.

### 7.5 Engine byte-identity guard

Ownbit precedent: `OB:core/jsengine/src/test/kotlin/io/ownbit/core/jsengine/JsBundleIntegrityTest.kt:10-26` pins
every bundle's SHA-256, so "any edit / EOL normalisation / accidental replacement fails the build", and cross-checks
the iOS md5 table. Signer:

```kotlin
class EngineIntegrityTest {
    @Test fun `signer js is the iOS bundle byte for byte`() {
        val f = File("src/main/assets/engine/signer.js")
        assertEquals(1_181_026L, f.length())
        assertEquals("6933d0d8a6f60faa8222506ac0bd7eef80eb884c9f73e31e9f354b4740f21077",
            MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(Locale.ROOT, it) })
    }
}
```

Add pins for `token-registry.json`, `known-contracts.json`, `TokenIcons.pack`, `TokenIcons.index.json`,
`bip39-*.txt` and the legal texts, all taken from the iOS resources at the same commit. Add a root `.gitattributes`
(neither repo has one) so Git never rewrites them:

```
*.js    -text
*.json  -text
*.pack  binary
*.txt   -text
```

Verified while writing this spec: `shasum -a 256` of `IOS:BitToolSigner/Resources/signer.js` and of
`SIG:core/engine/src/main/assets/engine/signer.js` are both `6933d0d8…f21077`.

### 7.6 Distribution artifact task (`apk` channel)

The new variant API has no setter for the output file name. Copy the APK instead:

```kotlin
androidComponents.onVariants(androidComponents.selector().withBuildType("release").withFlavor("distribution" to "apk")) { variant ->
    tasks.register<DistApk>("distApkRelease") {
        apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
        loader.set(variant.artifacts.getBuiltArtifactsLoader())
        outDir.set(layout.buildDirectory.dir("dist"))
    }
}
// DistApk: loads BuiltArtifacts, copies the single APK to "BitToolSigner-<versionName>-<versionCode>.apk",
// writes "<name>.sha256" in `shasum -a 256` format, and fails if the APK's assets/engine/signer.js digest is not the pin.
```

---

## 8. Localization

### 8.1 Ownbit `:core:l10n` layout

- `OB:core/l10n/build.gradle.kts:1-27`: android library + hilt; `lint { disable += "MissingTranslation";
  disable += "LocaleFolder" }`; `api(libs.androidx.appcompat)`, so every user of `LanguageManager` gets
  `AppCompatDelegate`; Robolectric for JVM tests.
- `src/main/res/values*/`: generated `strings_ios.xml`, `strings_membership.xml`, `strings_securitycenter.xml` and
  `strings_composite.xml` ("GENERATED FILE - DO NOT EDIT"); generated from JSON `strings_public_protocol.xml`;
  hand-written `strings_android_only.xml` (`l10n__*` names).
- Kotlin: `AppLanguage.kt`, `LanguageManager.kt`, `AppStrings.kt`, `internal/LanguageStore.kt`,
  `internal/LocalePlatform.kt`, `LocalizedDates.kt`, `LocalizedServerText.kt`, `ErrorCodeStrings.kt`.
- Tests: `StringResourceFormatTest`, `RuntimeResourcesTest` (`@Config(sdk = [26, 35])`), `LanguageManagerTest`,
  `AppLanguageTest`, `LocalesConfigConsistencyTest`, …

Resource directories (`OB:tools/strings-import/strings_import/config.py:50-75`,
`OB:tools/strings-import/README.md`):

| iOS lproj | Android dir(s) | Note |
|---|---|---|
| en | `values` | default; English fallback |
| zh-Hans / zh-Hant | `values-b+zh+Hans` / `values-b+zh+Hant` | BCP-47 script qualifiers |
| ja ko ru es de fr it tr th vi pl uk ar | `values-<code>` | |
| pt-BR / pt-PT | `values-pt-rBR` / `values-pt-rPT` | no `values-pt` |
| id | `values-in` **and** `values-b+id` | the framework resolves `id`, `id-ID`, `in` and `in-ID` to `values-in` (`ResourcesImpl.adjustLanguageTag`, verified on API 26 and 35); `b+id` is insurance. `values-id` must never be added (same config as `b+id` → aapt2 duplicate) |
| fil | `values-b+fil` | same config as `values-fil`; never ship both |
| es-419 | `values-b+es+419` | Ownbit writes only the strings that differ from `values-es` (Android falls back per string to the parent) |

### 8.2 Ownbit importer (`OB:tools/strings-import`): rules to keep

Usage (`OB:tools/strings-import/README.md`, `import_strings.py:1-108`): `python3 tools/strings-import/import_strings.py`
(idempotent) and `--check` (CI: exits 1 when outputs are stale); `validate_formats.py`;
`python3 -m unittest discover -s tools/strings-import/tests`; then
`./gradlew :core:l10n:assembleDebug :core:l10n:testDebugUnitTest`. Standard library only, Python 3.8+. Outputs are
written only when changed, with LF line endings. The pipeline is pure (no timestamps), so two runs produce identical
bytes (`pipeline.py:1-6`).

1. **Names** (`android.py:44-71`): lower-case; runs of non-`[a-z0-9]` → `_`; iOS placeholders inside keys removed;
   a leading digit gets `n_`; Java/Kotlin keywords get `_str`; a key with no usable character becomes
   `key_<sha1[:8]>`. Collisions: the key that already equals the name keeps it, otherwise the first in file order;
   the others get `_2`, `_3` (`NameAllocator`, `:74-137`). **Names are persisted in `key_map.tsv`** (`table, iosKey,
   androidName, englishValue, formatArgs`) and reused, "so an iOS sync never renames an existing resource". Generated
   names never contain `__`.
2. **Placeholders** (`formats.py:1-212`): strict iOS pattern `%%|%[N$][.P][ll|l|h|q|z](@|d|i|u|f|s)`, so literal `%`
   in prose is never taken for a placeholder. A key is a *format key* when its **English** value has ≥ 1 placeholder.
   Conversions: `%@`/`%s` → `s`; `%ld`/`%lld`/`%i`/`%u`/`%d` → `d`; `%.2f` kept; existing `%N$` kept; when the key
   takes more than one argument, **every** placeholder is written positional (`%1$s`, also in English); a lone `%`
   in a format string → `%%`. *Plain keys* are copied verbatim and get `formatted="false"` when they contain `%`.
   Repairs: `% @` → `%@`, `% %@` → ` %@`, and a space is inserted when the placeholder is glued to a word. A
   translation that repeats the single English argument is mapped to `%1$s`; a kind mismatch becomes `%s`; an
   unknown argument index drops the translation (English fallback, reported).
3. **Escaping** (`android.py:166-204`): `\` → `\\`, `"` → `\"`, `'` → `\'`, newline → `\n`, tab → `\t`, CR → `\n`,
   `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;`; a leading `@` or `?` gets a `\`. The value is wrapped in `"…"` when it
   has leading or trailing spaces, a double space, **or any non-ASCII whitespace** (U+3000 ja, U+00A0 fr/ru,
   U+202F). Outside quotes aapt2 collapses whatever the host `iswspace` calls whitespace; an unquoted U+3000 compiled
   to an ASCII space, which `RuntimeResourcesTest` caught. Characters XML 1.0 cannot carry are removed and reported
   (one ko string had 0x10).
4. **Report** `missing_report.txt`: missing or empty translations, keys only in a translation, duplicates,
   placeholder repairs, dropped translations, stripped control characters, name collisions, removed keys.
5. **Composites** (`config.py:152-186`): a complete format string built from iOS fragments wherever iOS
   concatenates in code, with no space for zh-Hans/zh-Hant/ja/th. Rule: "Add new composites there rather than
   concatenating in Kotlin."
6. **Android-only strings** live in a JSON with all 21 languages and their own generator plus `--check`
   (`sc_android_strings.json`, `public_protocol_strings.json`, README "Android-only security-center strings" and
   "Android-owned public-protocol strings"): placeholders Android-style and always positional, no `%` in strings
   without placeholders, brand names kept, a check of how the user is addressed, length budgets, `_sameAs` links
   to imported strings.
7. **Validation**: `validate_formats.py:1-158` runs `java.util.Formatter` rules over every value for the English
   argument kinds, the aapt2 multi-substitution rule ("aapt2 rejects >1 non-positional substitution") and the
   unquoted Unicode whitespace check. `StringResourceFormatTest` runs every string of every language through the
   real `java.util.Formatter`. `RuntimeResourcesTest` (Robolectric, real aapt2 output, real framework locale
   resolution, API 26 + 35) asserts that every compiled string equals the generated text, that each language
   resolves its own directory, and that fallback works. `LocalesConfigConsistencyTest` asserts that
   `app/src/main/res/xml/locales_config.xml` lists exactly the `AppLanguage` tags.
8. Plurals: imported iOS text has none. Ownbit uses `<plurals>` only for Android-only strings, with CLDR
   categories: `other` only for zh/ja/ko/th/vi/id; `one`+`few`+`many`+`other` for ru; `one`+`other` elsewhere
   (`OB:docs/qa/i18n.md:50-51`).

### 8.3 The signer's iOS catalogue (facts the importer must handle)

Inputs: `IOS:engine/data/interface-strings.json`, an ordered list of **773** English keys ("Xcode's own extraction
plus the static text the offline engine produces", `IOS:scripts/write-localizations.py:6-11`), and
`IOS:translations/<lang>.json` = `{"language", "name", "strings": {english: translation}, "infoPlist":
{NSCameraUsageDescription, NSFaceIDUsageDescription}}` for 20 languages (English is the key itself). The scaffold
copies them to `SIG:tools/l10n/`.

Measured over all files:
- Specifiers in keys: only `%@` (27 occurrences), `%lld` (36) and `%%` (1, in `"%lld%%"`). There are **52 format
  keys**; 11 take ≥ 2 arguments: `"%@ address #%lld"`, `"%@: %@…"`,
  `"%lld contract addresses published by the projects themselves. … Updated %@."`, `"%lld/%lld"`,
  `"Animated QR code, part %lld of %lld"`, `"Card %lld of %lld"`, `"Page %lld of %lld"`,
  `"Showing the first %@ of %@ characters. Copy includes everything."`, `"Signature %lld of %lld"`,
  `"Step %lld of %lld"`, `"Word %lld, %@"`.
- Translations already use positional iOS specifiers in 9–10 strings per language (e.g. zh-Hans
  `"%@ address #%lld"` → `"%1$@ 地址 #%2$lld"`, `"%lld/%lld"` → `"%1$lld/%2$lld"`). The iOS rule is that the
  specifier sequence matches the English one exactly, or the translation is fully positional and covers 1..n with
  the same kinds; a key that repeats a specifier type *must* be positional (`write-localizations.py:47-77`).
- No plural forms (no `.stringsdict`): counts are format strings (`"%lld words"`, `"%lld wallets"`, `"%lld tokens"`)
  plus separate singular keys (`"1 word"`, `"12 words"`, `"24 words"`). Translators avoided case problems by
  rephrasing (ru `"Кошельков: %lld"`, pl `"Portfele: %lld"`, uk `"Гаманців: %lld"`). **Keep them 1:1 as
  `<string>`.** Do not invent `<plurals>`, because iOS has no translations for the other forms.
- English keys: 5 ASCII apostrophes (e.g. `"Some words don't match. Check your written backup and try again."`) and
  18 U+2019 (`’`); U+2026 `…` ×5; U+00B7 `·` ×11; U+2013 ×3; U+2014 ×2; U+201C/U+201D ×1; **U+2011
  non-breaking hyphen** ×1 (`"Wi‑Fi"`); U+2022 ×1; `&` in `"Networks, Tokens & Contracts"` and
  `"Run: cd engine && npm ci && npm run build"`. No ASCII `"..."`, no newline, no tab, no leading `@`/`?`, no
  leading or trailing space.
- Translations: **no** U+00A0 / U+202F / U+3000 in any language today (fr uses an ordinary space before `:`), but
  the importer must still quote such values if they appear. ar has U+200F (RLM) ×5 and Arabic punctuation (U+060C
  ×60, U+061F, U+061B); ja and zh use full-width punctuation (U+3002, U+3001, U+FF0C, U+FF1A, …); fil has 2 ASCII
  apostrophes (must be escaped `\'`). All of these are carried verbatim as UTF-8.
- Literal Latin digits inside plain translations: ar `"12 words"` → `"12 كلمة"`. This argues for Latin digits
  everywhere in Arabic (§8.7.5).
- `infoPlist.NSCameraUsageDescription` (English source in the Xcode project:
  `"BitTool Signer scans signing requests from your other wallet and recovery-phrase QR codes. Camera images are processed on this device and never leave it."`,
  `IOS:BitToolSigner.xcodeproj/project.pbxproj:423`). Android has no system usage string. The scaffold imports it
  as `camera_usage_description` for an in-app rationale. `NSFaceIDUsageDescription`
  (`"Use Face ID to open BitTool Signer. Signing requires your wallet password."`, `:424`) is iOS-only: do not
  import it.

### 8.4 Engine text: runtime English lookup

`IOS:BitToolSigner/Models/ReviewText.swift:12-54`. "The engine writes its field labels, action names and headlines
in English and nothing translates them there: that text is what the request fingerprint covers … The interface
translates at the last moment instead, when the words are drawn, and falls back to the engine's own English
whenever a phrase has no translation."
- `phrase(english)`: a whole-phrase lookup; the English text when there is no entry.
- `headline(english)`: a whole-phrase lookup; otherwise, for the prefixes `"Method "`, `"Safe: "`, `"Batch: "`,
  `"New "`, translate the prefix without its trailing character (`dropLast`) and append `" " + value`.
- `label(english)`: a whole-phrase lookup; otherwise, if it ends in `")"`, translate the stem before the last `"("`
  (trimmed) and append `" " + "(unit)"` unchanged.
- The lookup is done in the *chosen* language's bundle (`AppText.bundle`, `IOS:BitToolSigner/Models/AppLanguage.swift:121-127`).

Android consequences:
- The catalogue keys must equal the engine's English **byte for byte**. The importer must never normalise
  typography, and lint's `TypographyEllipsis` / `TypographyQuotes` must stay suppressed on generated files.
- The scaffold's generated `IosStrings.byEnglish: Map<String, Int>` (English → `R.string`) is the right design. It
  references `R.string` statically, so R8 keeps the resources. Resolve the id with the activity/Compose context, or
  with an `AppStrings`-style localized context outside Compose (§8.7.4). Never use the application context below
  API 33.

### 8.5 Review of the scaffold importer (`SIG:tools/l10n/import_ios_strings.py`)

Correct already: strict completeness (exits on a missing string); `%@`→`%s` and `%lld`→`%d`; positional output when
the source has ≥ 2 specifiers, keeping a translation's own `N$`; escaping of `\ ' " & < > \n \t` and a leading
`@`/`?`; `formatted="false"` for plain strings containing `%`; the English→id Kotlin map; the TSV index.

Gaps compared with Ownbit, with fixes:
1. **Names are not persisted.** `slug()` truncates at 56 characters and de-duplicates by order (`_2`), so removing
   or reordering an iOS key can rename other resources. Read `strings-index.tsv` back first and reuse recorded names,
   as Ownbit's `key_map.tsv` does.
2. **Whitespace.** It uses ` ` for leading, trailing and doubled spaces, which is fine, but non-ASCII
   whitespace is not protected. Quote the whole value as in Ownbit `android.py:199-203`.
3. **Specifier check** is a sorted comparison of kinds. Use the iOS rule (exact sequence, or full positional
   coverage) so a swapped translation can never pass.
4. **No `--check` mode** and no Formatter validation. Add `--check` and a `StringResourceFormatTest` /
   `RuntimeResourcesTest` pair in `:core:l10n` (Robolectric, `@Config(sdk = [26, 36])`).
5. **Directories.** It writes pt-PT into **`values-pt`** as well as `values-pt-rPT`. A region-less `pt` device (and
   `pt-AO`, `pt-MZ` via `values-pt`) would then get European Portuguese, while Ownbit maps region-less pt to pt-BR
   (`OB:core/l10n/src/main/kotlin/io/ownbit/core/l10n/AppLanguage.kt:99,113`). Drop `values-pt`; Android's
   parent-locale data already resolves pt-AO/pt-MZ to pt-PT. The extra copies in `values-zh-rCN`, `-zh-rTW` and
   `-zh-rHK` are unnecessary: API 24+ infers the script (zh-TW/zh-HK → Hant, zh-CN → Hans) and matches
   `b+zh+Hant`/`b+zh+Hans`. Ownbit ships none. Keep them only if `RuntimeResourcesTest` shows a gap.
   `values-fil` + `values-tl` is acceptable (fil and tl are distinct codes to the resource system), but never also
   `values-b+fil`.
6. **Headers.** Add `tools:ignore="MissingTranslation,UnusedResources"` on `values/` and the typography ignores on
   all generated files.
7. **Android-only strings** (§8.8): add a JSON source with all 21 languages, in the same schema as the iOS
   translation files so iOS could adopt it, and merge it in the same run into `strings_android.xml`.

### 8.6 `localeFilters` / `resourceConfigurations` and `locales_config.xml`

Ownbit (`OB:app/build.gradle.kts:113-120`):
`"en", "b+zh+Hans", "b+zh+Hant", "zh-rCN", "zh-rTW", "zh-rHK", "ja", "ko", "ru", "es", "b+es+419", "de", "fr", "it",
"pt", "pt-rBR", "pt-rPT", "in", "b+id", "tr", "th", "vi", "b+fil", "fil", "tl", "pl", "uk", "ar"`.
Quoted rationale: "This also drops the en-rXC / ar-rXB pseudo-locales: with the per-app locale "en" they won
resource matching and library strings came out wrapped in U+200E/U+200F marks (QA wave 4). The filter matches a
region exactly ("es" does not keep values-b+es+419): every regional resource directory of a new language must be
listed here too." The `zh-r*` and `pt` entries keep the **libraries'** regional directories (AndroidX/Material ship
`values-zh-rCN/-rTW/-rHK` and `values-pt`). A missing entry "strips that language's strings from the APK (and the
unit-test resources), so the app falls back to English" (`OB:docs/impl/modules/app.md`, §9).

The signer's list (`SIG:app/build.gradle.kts`) has the same members. Keep it, and add a unit test that every
`values-*` directory of `:core:l10n` is matched by a filter entry.

`OB:app/src/main/res/xml/locales_config.xml:1-24` (hand-written, `generateLocaleConfig = false`), in picker order:
`en, zh-Hans, zh-Hant, ja, ko, ru, uk, es-ES, es-419, de, fr, it, pt-BR, pt-PT, pl, id, tr, th, vi, fil, ar`.
Ownbit uses `es-ES` rather than `es` so Android 13+ Settings shows "Español (España)" next to
"Español (Latinoamérica)". The signer should copy this file unchanged; its 21 languages are exactly the same set
(`IOS:BitToolSigner/Models/AppLanguage.swift:11-31`). Do not let AGP generate it: it would list every `values-*`
directory, including `in`, `tl` and `zh-rCN`.

Manifest (`OB:app/src/main/AndroidManifest.xml:28,59-67`): `android:localeConfig="@xml/locales_config"` plus

```xml
<service android:name="androidx.appcompat.app.AppLocalesMetadataHolderService"
    android:enabled="false" android:exported="false">
    <meta-data android:name="autoStoreLocales" android:value="true" />
</service>
```

This is "Per-app language persistence for API < 33 (AppCompatDelegate.setApplicationLocales)". The signer manifest
already declares it.

### 8.7 Per-app language: Ownbit implementation, iOS behaviour, signer recommendation

#### 8.7.1 Ownbit `AppLanguage` (`OB:core/l10n/.../AppLanguage.kt:28-132`)

`enum AppLanguage(lproj, languageTag, nativeName)`: `EN("en","en","English")`, `ZH_HANS(…"简体中文")`,
`ZH_HANT(…"繁体中文")`, `JA "日本語"`, `KO "한국어"`, `RU "русский"`, `UK "Українська"`,
`ES("es","es-ES","Español (España)")`, `ES_419("es-419","es-419","Español (Latinoamérica)")`, `DE "Deutsch"`,
`FR "Français"`, `IT "Italiano"`, `PT_BR "Português (Brasil)"`, `PT_PT "Português (Portugal)"`, `PL "Polski"`,
`ID "Bahasa Indonesia"`, `TR "Türkçe"`, `TH "ภาษาไทย"`, `VI "Tiếng Việt"`, `FIL "Filipino"`, `AR "العربية"`.
`platformTag = if (AR) "ar-u-nu-latn" else languageTag`, so `%d`, dates and every number use 0–9 (`:68`).
`isRtl = AR`. `fromLocale` (`:103-118`): zh without a script → TW/HK/MO = Hant, otherwise Hans; es → es-419 for
`419, AR, BO, BR, BZ, CL, CO, CR, CU, DO, EC, GT, HN, MX, NI, PA, PE, PR, PY, SV, US, UY, VE`, otherwise es;
pt → BR or no region = pt-BR, otherwise pt-PT; `id`/`in` → ID; `fil`/`tl` → FIL; otherwise an exact match or EN.

**The signer's iOS endonyms differ**: use them, never Ownbit's (`IOS:BitToolSigner/Models/AppLanguage.swift:35-60`):
"English", "简体中文", **"繁體中文"**, "日本語", "한국어", **"Русский"**, "Українська", "Español (España)",
"Español (Latinoamérica)", "Deutsch", "Français", "Italiano", "Português (Brasil)", "Português (Portugal)",
"Polski", "Bahasa Indonesia", "Türkçe", "ภาษาไทย", "Tiếng Việt", "Filipino", "العربية". The first entry is
`system`, shown as the **localized** string `"System"` (`IOS:…/SettingsTab.swift:53-56`). The raw values are the
iOS lproj codes (`"zh-Hans"`, `"es-419"`, …), stored under the UserDefaults key `"language"` with default `"system"`
(`IOS:BitToolSigner/BitToolSignerApp.swift:10`). Endonyms are never translated ("a list of languages is read by
people who do not yet read the current one", `AppLanguage.swift:6-7`). `selectable` filters to bundled
localizations (`:65-67`); all 21 are bundled.

#### 8.7.2 Ownbit `LanguageManager` (`OB:core/l10n/.../LanguageManager.kt:19-241`)

- Until the user picks, the app follows the system language. While following, it still applies the mapped language
  as the per-app locale (the "follow pin", persisted as `follow_system_pin`). An explicit pick is persisted as
  `bil_languages` in the SharedPreferences file `ownbit_l10n` (`internal/LanguageStore.kt:37-63`). SharedPreferences
  are used, not DataStore, because the value is needed synchronously in `Application.onCreate`.
- `initialize()` runs from `Application.onCreate()` on the main thread before the first activity (`:86-103`,
  `OB:app/.../OwnbitApplication.kt:33-39`). It applies the platform tag unless that tag is already applied, then
  registers the config listener.
- `setLanguage()` is a no-op when the language is already current. Otherwise it persists the choice, clears the pin
  and runs `applyAppLocale` on main (`:111-119`), which recreates the activities.
- `syncFromPlatform()` runs on every configuration change **and** on `ACTION_LOCALE_CHANGED`. While a per-app
  locale is applied, a system-language change produces no `onConfigurationChanged`. The receiver is registered with
  `RECEIVER_EXPORTED` (it is a protected broadcast, `LocalePlatform.kt:102-118`). Behaviour: an empty per-app
  locale (Android 13+ Settings → "System default") means follow again. A tag that is not our pin was picked in
  system Settings, so it becomes the explicit choice. `ar` from Settings is re-applied as `ar-u-nu-latn`
  (`:128-191`).
- `LocalePlatform` (`internal/LocalePlatform.kt:43-129`): on API 33+ it uses the framework `LocaleManager`. Quoted:
  "AppCompat's API 33 path needs a live AppCompat activity and silently does nothing from Application.onCreate". On
  API 26–32 it uses `AppCompatDelegate.setApplicationLocales`. `resourceLocaleTag("en") = "en-US"`, because for
  plain `en` the matcher preferred a library `values-en-r??` (pseudo-locale `en-rXC`) over `values/`.
- `updateNumberRegion()`: when the device language equals the UI language, the device region refines the number
  separators (`:213-223`, `OB:docs/impl/l10n-numbers-rtl.md` §1).
- `AppStrings` (`OB:core/l10n/.../AppStrings.kt:11-65`): string lookup outside Compose through a cached
  `createConfigurationContext(LocaleList(language.locale))`, because "on API < 33 per-app locales are applied to
  activities only, so the application context would answer in the system language".
- Picker (`OB:feature/settings/.../language/LanguageSelectViewModel.kt:39-101`): search over native name, lproj and
  `getDisplayName(uiLocale)`; picking calls `setLanguage`, then waits `RECREATE_GRACE_MS = 350` before the
  "back + Saved" event so the toast appears in the recreated window.
- `MainActivity` `configChanges` has **no** `locale|layoutDirection` (`OB:app/src/main/AndroidManifest.xml:41`), so
  the platform recreates the activity. Compose `stringResource` then resolves the new language and RTL mirrors.

#### 8.7.3 iOS signer behaviour to match

- Changing the language rebuilds the whole tree with `.id(language.rawValue)` and "returns to the first tab"
  (`IOS:BitToolSigner/Models/AppLanguage.swift:93-111`). Android activity recreation keeps the navigation state
  instead; that is an accepted Android deviation (`app-shell.md` §15).
- The layout direction is RTL for Arabic, LTR for every other explicit language, and follows the device language
  for `system` (`AppLanguage.swift:73-79,108-110`). Android gets this from the locale with `android:supportsRtl="true"`.
- Footer under Appearance: `"The interface language. What a signing request itself says is decided by the request and stays as it is."`
  (`IOS:…/SettingsTab.swift:64`).

#### 8.7.4 Signer recommendation

- **Source of truth** = the platform's per-app locale (`AppCompatDelegate.getApplicationLocales()`; on API 33+
  `LocaleManager.applicationLocales`). `system` = empty list. No extra SharedPreferences value is needed, because
  `autoStoreLocales` (API < 33) and the framework (33+) persist it. On API < 33 the stored value is only re-applied
  when the first `AppCompatActivity` is created, so do not read it in `Application.onCreate`.
- Apply from the UI (activity alive) with `AppCompatDelegate.setApplicationLocales(
  LocaleListCompat.forLanguageTags(tag))` or `LocaleListCompat.getEmptyLocaleList()`, on the main thread. On API
  33+, `LocaleManager` directly is the robust path (Ownbit's finding).
- Tags: as Ownbit's `locales_config` (`es-ES` for Spain). `en` → apply `en-US`, or rely on `localeFilters` having
  removed `en-rXC`. Both mitigations are cheap; keep the en-US mapping.
- **Following the system with an empty per-app locale.** The framework picks resources from the system locale list
  (`LocaleList.getFirstMatchWithEnglishSupported`, which treats English as supported). Ownbit's comment claims a
  system list like `[en, de]` would resolve German without its pin (`LanguageManager.kt:35-36`), and
  `activity-settings.md` repeats this. **Verify with a Robolectric test** (`Configuration.setLocales(LocaleList(en-GB,
  de-DE))` + `createConfigurationContext` → expect the English `values/` text) before adding Ownbit's pin machinery.
  If the test fails, either generate a `values-en` copy or pin the mapped language like Ownbit.
- Mapping for display and for the picker's check mark while following: reuse Ownbit's `fromLocale` rules (zh
  region → script, es-419 regions, pt region-less → BR, `in`/`tl`).
- Strings outside Compose (errors built in ViewModels, the engine-text lookup): an `AppStrings` equivalent that
  resolves against the current per-app locale (iOS `AppText.bundle`, `AppLanguage.swift:121-127`).
- `MainActivity` must be an `AppCompatActivity`. A plain `ComponentActivity` silently ignores per-app locales below
  API 33. It is also a `FragmentActivity`, which `BiometricPrompt` needs if App Lock uses it.

#### 8.7.5 Digits and RTL (Arabic)

Ownbit applies Arabic as `ar-u-nu-latn` and uses Latin digits everywhere (`AppLanguage.kt:19-21,68`). It writes
machine text with `Locale.ROOT` and never formats a value for sending, copying or persisting with the default
locale (`OB:docs/impl/l10n-numbers-rtl.md` §1). Recommended for the signer too: addresses, hashes, amounts, the
engine digest and certificate fingerprints are Latin, and translations already contain Latin digits (§8.3). This is
a **visible deviation** from iOS SwiftUI, which formats `%lld` / `.formatted()` in the `ar` locale, probably with
Arabic-Indic digits. Owner decision; default to Latin. If adopted, add Ownbit's re-apply of `ar` → `ar-u-nu-latn`
when Android Settings sets plain `ar` (`LanguageManager.kt:149-152,188-191`). RTL rules worth copying
(`l10n-numbers-rtl.md` §3): typography with `TextDirection.Content`; `ForceLtr {}` for numeric keypads, sliders and
charts; LTR styling for addresses, keys, hashes, JSON and signed amounts; `Icons.AutoMirrored.*` for directional
arrows; mirror absolute offsets manually.

Hex for display: never `"%02x".format(b)` with the default locale. Use `Locale.ROOT` or a lookup table.

### 8.8 Android-only strings the signer will need (not in the iOS catalogue)

Each needs all 21 languages through the JSON source of §8.5 item 7. Placeholder wording is listed here; final copy
belongs to the owner. iOS-specific wording that must be replaced (biometrics, Photos, iCloud, Files, App Store) is
inventoried in `activity-settings.md` §12.3 and `app-shell.md`. The build-identity rows of §11 add:
- `"Distribution"`, `"Google Play"` and `"Direct download (APK)"`.
- `"Signing certificate"` (row label, value = SHA-256 lowercase hex).
- apk footer: `"The SHA-256 of the certificate Android checked when it installed this app. Compare it with the fingerprint published at https://bittool.com/signer; a different value means this copy was not signed by BitTool."`
- An APK verification guide (apk only, text only, because the app never opens a browser):
  `"Verify the APK before installing"`; steps `"On a computer, run: apksigner verify --print-certs <file>.apk"`,
  `"Check that the certificate SHA-256 matches the fingerprint published at https://bittool.com/signer."`,
  `"After installing, the same value appears here under Signing certificate."`

---

## 9. App shell: Ownbit `Application` / `Activity`, signer adaptation

### 9.1 Manifest `<application>` (`OB:app/src/main/AndroidManifest.xml:19-33`)

`android:allowBackup="false"`, `dataExtractionRules="@xml/data_extraction_rules"`,
`fullBackupContent="@xml/full_backup_content"`, `enableOnBackInvokedCallback="true"` (predictive back),
`localeConfig`, `networkSecurityConfig` (drop for the signer: there is no network), `supportsRtl="true"`, base theme
on the application, splash theme on the activity.

`OB:app/src/main/res/xml/data_extraction_rules.xml:1-18` excludes `root`, `file`, `database`, `sharedpref` and
`external` in both `<cloud-backup>` and **`<device-transfer>`**. On Android 12+ `allowBackup="false"` stops cloud
backup but **not** device-to-device transfer, so the device-transfer exclusions are mandatory. If the signer ever
uses device-protected storage, also exclude `device_root`, `device_file`, `device_database` and
`device_sharedpref`. `full_backup_content.xml` has the same five excludes for API < 31. (The scaffold uses
`android:fullBackupContent="false"`; an explicit exclude-all XML like Ownbit's is the more explicit form.)

Activity (`:35-57`): `exported="true"`, `launchMode="singleTask"`, `theme="@style/Theme.Ownbit.Splash"`,
`windowSoftInputMode="adjustResize"`,
`configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density|uiMode"` (no
`locale`/`layoutDirection`), and a MAIN/LAUNCHER filter. Ownbit's deep-link schemes do not apply: the iOS signer
declares no URL types (no `CFBundleURLTypes` in `IOS:BitToolSigner.xcodeproj/project.pbxproj`). The signer manifest
has only MAIN/LAUNCHER, no `<queries>`, and no exported component besides the launcher activity.

### 9.2 Themes (`OB:app/src/main/res/values/themes.xml:1-32`, `values-night/themes.xml:1-5`)

```xml
<style name="Theme.Ownbit" parent="Theme.AppCompat.DayNight.NoActionBar">
    <item name="android:windowBackground">@color/launch_background</item>
    <!-- also the page the system shows for a FLAG_SECURE task snapshot (AppCompat dark default was grey #303030) -->
    <item name="android:colorBackground">@color/launch_background</item>
    <item name="android:statusBarColor">@android:color/transparent</item>
    <item name="android:navigationBarColor">@android:color/transparent</item>
    <item name="android:windowLightStatusBar">@bool/ownbit_light_system_bars</item>
    <item name="android:windowLightNavigationBar" tools:targetApi="27">@bool/ownbit_light_system_bars</item>
    <item name="android:enforceNavigationBarContrast" tools:targetApi="29">false</item>
    <item name="android:enforceStatusBarContrast" tools:targetApi="29">false</item>
</style>
<style name="Theme.Ownbit.Splash" parent="Theme.SplashScreen">
    <item name="windowSplashScreenBackground">@color/launch_background</item>
    <item name="windowSplashScreenAnimatedIcon">@drawable/splash_icon</item>
    <item name="windowSplashScreenAnimationDuration">1000</item>
    <item name="postSplashScreenTheme">@style/Theme.Ownbit</item>
</style>
<!-- launch_background #FEFFFF (night #21252A); ownbit_light_system_bars true (night false) -->
```

The bar attributes are needed because "androidx splashscreen re-applies these theme values to the window on Android
12+ … without them a launcher cold start left white status / navigation bar icons on the app's light pages"
(`themes.xml:10-13`). The splash icon is an animated-vector with a no-op animation, so Android 12+ draws it as a
vector (`OB:docs/impl/modules/app.md` §9). The signer's launch visuals (brand tile, colours) are in `app-shell.md`
§15.3.

### 9.3 `Application` (`OB:app/src/main/kotlin/io/ownbit/app/OwnbitApplication.kt:24-40`)

`@HiltAndroidApp`. Order in `onCreate`: (1) `languageManager.initialize()` first, on main, so the first activity
already has the language; (2) `CoinIcons.preload(this)` parses the token-icon manifest off the main thread;
(3) app integration; (4) `appStartup.start()`, which returns immediately. Signer `SignerApplication`: (1) apply the
persisted appearance as the AppCompat night mode from a synchronous read, and on API 31+ also
`UiModeManager.setApplicationNightMode` so the system splash follows the in-app setting (`app-shell.md` §15);
(2) preload `TokenIcons.index.json` off the main thread; (3) nothing that touches the network, analytics, Firebase
or WorkManager. Language needs no init unless the pin of §8.7.4 is adopted.

### 9.4 `MainActivity` (`OB:app/src/main/kotlin/io/ownbit/app/MainActivity.kt:30-137`)

- `class MainActivity : AppCompatActivity()` ("AppCompatActivity for per-app locales below API 33"), `@AndroidEntryPoint`.
- `installSplashScreen()` before `super.onCreate`. `splash.setKeepOnScreenCondition { launchScreen.keepOnScreen(decor) }`.
  `LaunchScreenGate` keeps the splash until the shell is ready and composed, and the PIN lock or a loaded home has
  drawn, for at most `MAX_FIRST_SCREEN_WAIT_MILLIS = 1_500L` after composition
  (`OB:app/.../LaunchScreenGate.kt:27-93`). `LaunchSplashState.active` stays true until the first frame after the
  splash has drawn.
- `enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))` and, on API
  29+, `window.isNavigationBarContrastEnforced = false`. The default contrast scrim "painted a white (light) /
  darker (dark) strip over the grey grouped pages" with 3-button navigation (`:110-121`). Edge-to-edge is enforced at
  targetSdk 35+ anyway; pages pad for insets themselves.
- Theme: it collects the persisted mode and calls `AppCompatDelegate.setDefaultNightMode(…)`, then
  `applyWindowBackground()` = `window.setBackgroundDrawable(ColorDrawable(getColor(R.color.launch_background)))` on
  every switch and in `onConfigurationChanged`. The window resolves its background only once, so after a theme
  switch the IME / predictive-back background kept the old colour (D18, `:84-100,123-131`). `OwnbitTheme` reads the
  **application** configuration for "system dark", because `isSystemInDarkTheme()` reflects the in-app AppCompat
  mode once applied (`OB:core/designsystem/.../theme/OwnbitTheme.kt:88-125`). Copy both fixes.
- Orientation: phones (sw < 600 dp) are locked to portrait (`:56-59`). **Do not copy** (§0 item 10). Android 16 /
  targetSdk 36 ignores orientation locks on large screens anyway (`OB:docs/qa/release.md:413-415`).
- `onNewIntent` / `handleIntent` with the `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` guard (`:72-82`) exist only for
  deep links. The signer has none.

Signer skeleton:

```kotlin
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)          // whole app, set once, never toggled (§10)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        splash.setKeepOnScreenCondition { !vm.shellReady.value }
        setContent { SignerAppRoot(vm) }
    }
}
```

### 9.5 Root composable (`OB:app/src/main/kotlin/io/ownbit/app/ui/OwnbitAppRoot.kt:90-216`)

It draws nothing until the shell is ready (the splash is still up). The structure is
`OwnbitTheme { OwnbitOverlayHost(windowGate) { NavHost + pop-up layers inside WindowGateScope; AppLockOverlay above
everything } }`. "Nothing above the lock": while locked, the gate holds every dialog, sheet, HUD and toast, and
releases them `UNLOCK_SETTLE_MILLIS = 450L` after unlock (`ui/LockWindowGate.kt:26-41`). A page covered by a pop-up
is hidden from TalkBack with `clearAndSetSemantics {}` (`OwnbitAppRoot.kt:163-188`). The signer's stage machine and
lock and cover layers are specified in `app-shell.md`.

---

## 10. Secure-window and lock patterns (Ownbit) and signer choices

1. **`SecureScreen(enabled)`** (`OB:core/ui/src/main/kotlin/io/ownbit/core/ui/security/SecureScreen.kt:7-23`,
   `core/ui/.../window/WindowEffects.kt:54-100`): FLAG_SECURE while the composable is in composition, reference
   counted per window "so two secure screens overlapping during a navigation transition never clear each other's
   protection". Inside a `Dialog` / `ModalBottomSheet` it targets that dialog's window, found through
   `DialogWindowProvider`. Ownbit applies it per sensitive page (mnemonic, verify, export key, imports, PIN, password
   dialogs; `OB:docs/qa/security-audit.md` §3).
2. **Never toggle the activity window's FLAG_SECURE.** "Toggling the activity window's FLAG_SECURE re-creates its
   surface on some devices (the owner's Huawei: a visible blink on every return), and the flag set in onPause often
   landed after the thumbnail was taken (emulator: 3 of 6 trials showed the wallet page)"
   (`OB:feature/security/.../lock/RecentsGuard.kt:28-31`).
3. **Recents thumbnail**: `Activity.setRecentsScreenshotEnabled(false)` on API 33+. On 26–32, an invisible 1×1
   `TYPE_APPLICATION` window with `FLAG_SECURE | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_NO_LIMITS`
   (`RecentsGuardWindow`, `RecentsGuard.kt:15-103`). The window manager replaces the snapshot of any task that holds
   a secure window with a plain page in `android:colorBackground`, hence §9.2.
4. **Lock window**: the lock screen is its own full-display `TYPE_APPLICATION` secure window. It is not a Compose
   `Dialog`, because Dialog measured at most `screenWidthDp × screenHeightDp` and the lock screen "collapsed" 72 dp
   when it took over (`OB:feature/security/.../lock/LockWindow.kt:39-56`). On unlock it is removed over an identical
   copy in the activity window, and that copy fades (`AppLockOverlay.kt:77-83`).
5. **Lock timing** (`OB:feature/security/.../lock/AppLockController.kt:23-59`): `ProcessLifecycleOwner` start/stop;
   the monotonic clock (`elapsedRealtime`, counts deep sleep); lock when
   `now < backgroundedAt || now - backgroundedAt > 60_000`; fail closed when the PIN store errors; a cold start
   always locks. Ownbit has **no biometric**: it uses a PIN plus wallet-password recovery. Signer: the iOS grace
   period is `backgroundGrace = 120` s, measured on a monotonic clock that keeps counting in sleep, and the app locks
   when `!(now >= since && now - since < 120)` (`IOS:BitToolSigner/Services/AppAccessController.swift:99-105,182-187`).
   The Android condition is therefore `elapsed >= 120_000 || elapsed < 0`, so 119 s stays unlocked and 120 s locks
   (`IOS:docs/Release-1.0.0.md:44-45`). Whether App Lock uses `BiometricPrompt` is decided in `app-shell.md` /
   `activity-settings.md` §3.1. If it does, the activity must be a `FragmentActivity` (`AppCompatActivity` is one),
   and the allowlist gains `USE_BIOMETRIC` / `USE_FINGERPRINT`.
6. **Clipboard** (`OB:core/ui/.../security/ClipboardHelper.kt:18-33`): `copySensitive` sets
   `ClipDescription.EXTRA_IS_SENSITIVE` and clears the clip after `SENSITIVE_CLEAR_DELAY_MS = 60_000L` unless the
   user copied something else. On API 33+ the system shows its own "Copied" confirmation, so the app should not show
   one. The signer keeps this pattern with the iOS duration of **two minutes** (the privacy text says the copy
   "expires after two minutes", `activity-settings.md` §10).
7. **Signer choice.** iOS hides the **whole app** when inactive or while the screen is captured, using a separate
   window above all sheets that shows the launch view and, while captured, the label
   `"Hidden while your screen is recorded or mirrored"` (`IOS:BitToolSigner/Services/PrivacyShield.swift:15-83`,
   label at `:75`). The Android equivalent is FLAG_SECURE on the activity window from `onCreate`, never toggled. It
   blanks screenshots, recordings, casting and the recents thumbnail for the whole app, and Compose
   `Dialog` / `Popup` / sheets created afterwards inherit it (`SecureFlagPolicy.Inherit` is the default; verify each
   window type with `adb shell screencap`, which must produce an empty image, as Ownbit QA did,
   `OB:docs/qa/release-smoke.md:47`). Recording detection for the label: `WindowManager.addScreenRecordingCallback`
   (API 35+, permission `DETECT_SCREEN_RECORDING`, a normal permission, so add it to the allowlist if used). Details
   are in `app-shell.md` §15. Debug builds may offer `-Psigner.allowScreenshots=true` (debug only, BuildConfig flag)
   for QA screenshots. Never in release.

---

## 11. Recommendation: how the signer's `apk` and `play` flavors should differ

The signer has no network, no updater and no outbound links (iOS: "The app never opens a browser" / "never opens a
mail app", `IOS:…/SettingsTab.swift:134,263`). Ownbit's differences (updater, install permission, FileProvider, rate
row) therefore all disappear. What remains:

| Aspect | `apk` (bittool.com/signer download) | `play` (Google Play) | Both |
|---|---|---|---|
| Manifest | nothing extra | nothing extra | CAMERA only (+ allowlist of §7.3). **No** INTERNET / ACCESS_NETWORK_STATE / REQUEST_INSTALL_PACKAGES / POST_NOTIFICATIONS; no FileProvider; no `<queries>` |
| Engine and data | — | — | byte-identical `assets/engine/signer.js` (`6933d0d8…1077`) and data files; same QuickJS |
| `BuildConfig.DISTRIBUTION` | `"apk"` | `"play"` | read once in `:app` → `BuildInfo(distribution, versionName, versionCode, applicationId, isDebug)` in `:core:common` (Ownbit `AppConfig` pattern, §4.2) |
| Settings › Version | — | — | `"$versionName ($versionCode)"` (§3.2) |
| Open Source › "This build" (iOS rows "App version", "Signing engine", "Engine size", "Token registry", "Networking code", `IOS:…/SettingsTab.swift:265-275`) | + "Distribution" = "Direct download (APK)"; + "Signing certificate" (SHA-256, read from PackageManager); apk footer of §8.8 | + "Distribution" = "Google Play"; + "Signing certificate" (equal to apk's under option A of §7.4) | the engine digest is computed on the device from the asset, as iOS does (`:310-330`). "Networking code" stays "None" because the build enforces it (§2.1, §7.3) |
| APK verification guide (text only, no links) | yes (strings in §8.8) | no | — |
| "Rate on Google Play" / store links | no | **no**: the app never opens another app, and the iOS signer has no App Store link. "Get it on Google Play" belongs on the website only | — |
| Legal texts | `app/src/apk/assets/legal/TERMS_OF_USE.txt`, `PRIVACY_POLICY.txt`: the direct-download clauses (Terms §15 "The App Store" dropped or rewritten, Privacy §10) | `app/src/play/assets/legal/…`: Google Play clauses | parser and structure identical (`activity-settings.md` §10). Flavor assets override nothing in library modules, which read the merged `assets/legal/` at runtime |
| Third-party notices | — | — | engine notices (`THIRD_PARTY_LICENSES.txt`, 92,925 B) **plus** the Android app's own dependency notices, generated at build time (`activity-settings.md` §9) |
| Signing | release key (its certificate is the published fingerprint) | upload key → Play App Signing with the **same** app signing key (§7.4 option A) | applicationId `com.bittool.signer`; same versionCode |
| Artifact | universal APK (ABIs arm64-v8a, armeabi-v7a, x86_64) + `.sha256` (§7.6) | AAB: language split **off**, ABI and density splits on. Optional `bundle { codeTransparency { … } }`, which lets users check that Play-delivered dex/native code matches; assets are covered by the signer.js digest | R8 identical; mapping archived per variant |
| Tests | `src/testApk` only if apk-only UI exists (guide visibility) | — | screenshot tests of the Open Source page for both values |
| Flavor code | `src/apk/kotlin/.../di/DistributionModule.kt` binds e.g. `ApkVerificationGuide` into an `Optional` (`@BindsOptionalOf` in `:feature:settings`) | `src/play/...` binds nothing | features never test `BuildConfig` |

Certificate fingerprint code (adapted from `OB:app/src/apk/kotlin/io/ownbit/app/update/ApkPlatform.kt:112-141`; the
same in both flavors, so it goes in `:core:common` or `:core:security`, not in a flavor source set):

```kotlin
fun signingCertificateSha256(context: Context): List<String> {
    val pm = context.packageManager
    val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
        val info = if (Build.VERSION.SDK_INT >= 33)
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        else pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.apkContentsSigners ?: emptyArray()           // current signer(s), also after v3 rotation
    } else {
        @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
    }
    val hex = "0123456789abcdef"
    return sigs.map { s ->
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { b -> "${hex[(b.toInt() shr 4) and 0xF]}${hex[b.toInt() and 0xF]}" }
    }
}
```

Display it in lowercase hex without separators, monospaced and selectable, the same format as the iOS engine digest
and as `apksigner verify --print-certs` prints "certificate SHA-256 digest", so users can compare character by
character.

---

## 12. Android mapping (concrete recommendations)

**Module placement**
- `build-logic/convention`: the plugins of §2.4 with the AGP 9 changes of §2.8, the Roborazzi gating of §2.6, the
  permission-allowlist task of §7.3 (in `bittool.android.application`), and `lint.targetSdk` /
  `testOptions.targetSdk` for libraries.
- `:app`: flavors, signing, `DistributionModule` per flavor, `MainActivity` (`AppCompatActivity`),
  `SignerApplication`, themes, `locales_config.xml`, `data_extraction_rules.xml`, `full_backup_content.xml`, flavor
  legal assets, `dist` task, legacy task aliases.
- `:core:common`: `BuildInfo` / `Distribution` (from `BuildConfig`, provided by `:app`), the `Log` facade (no-op in
  release), and `Locale.ROOT` helpers for machine text.
- `:core:l10n`: generated `strings_ios.xml` + `strings_android.xml`, `IosStrings` (English → id),
  `ReviewText`-equivalent helpers (phrase / headline / label), `AppLanguage` (iOS raw values + endonyms + Android
  tags), a language controller over `AppCompatDelegate` / `LocaleManager`, `AppStrings`, and tests
  (`StringResourceFormatTest`, `RuntimeResourcesTest`, `LocalesConfigConsistencyTest`, a filter-coverage test, and
  the "system list [en-GB, de-DE] → English" test).
- `:core:engine`: `assets/engine/signer.js` + `EngineIntegrityTest` (§7.5).
- Every module: a no-network boundary test (§2.1).

**Compose / Android APIs**
- Language picker: a full-screen list (`LazyColumn` with a `RadioButton` or check per row) with "System" first, then
  the endonyms. Each row's `Modifier.semantics` should carry the language so TalkBack reads the endonym in the right
  voice (`LocaleSpan` / `Modifier.semantics { … }`).
- Version / Open Source rows: Material 3 `ListItem` in grouped cards. Use `SelectionContainer` for digests and
  fingerprints, and the sensitive-clipboard helper for copy where relevant (fingerprints are not secret: a plain
  copy is fine).
- Per-app locale: `AppCompatDelegate.setApplicationLocales` / `getApplicationLocales`; `LocaleManager` on API 33+.
- Secure window: `WindowManager.LayoutParams.FLAG_SECURE`, `Activity.setRecentsScreenshotEnabled(false)`, and
  `WindowManager.addScreenRecordingCallback` (API 35+).
- Splash: `androidx.core:core-splashscreen` `installSplashScreen()` + `setKeepOnScreenCondition`.
- Edge-to-edge: `enableEdgeToEdge(SystemBarStyle.auto(TRANSPARENT, TRANSPARENT))`,
  `isNavigationBarContrastEnforced = false`.

**Pitfalls (summary)**
1. Applying `org.jetbrains.kotlin.android` or using `com.android.build.gradle.*` types under AGP 9 (§2.8).
2. Script-object captures in `doFirst` with the configuration cache on (§3.9).
3. `packaging.resources.excludes` does not drop AAR assets: use `ignoreAssetsPattern` and repeat aapt's default
   pattern (§3.7).
4. A missing `localeFilters` entry, `bundle.language.enableSplit = true`, or a `ComponentActivity` each silently
   break languages (§8.6, §8.7).
5. Typography normalisation of keys breaks engine-text lookup (§8.4).
6. Unpersisted resource names rename on iOS re-imports (§8.5).
7. `object` navigation routes without the `INSTANCE` keep rule crash only in release (§5).
8. A Google-generated Play app signing key means a channel switch requires an uninstall, which wipes the vaults
   (§7.4).
9. Missing device-transfer exclusions in `data_extraction_rules` (Android 12+ ignores `allowBackup` for
   device-to-device transfer, §9.1).
10. Default-locale `String.format` for hex or digits (Arabic) (§8.7.5).
11. Copying Ownbit's portrait lock, `ndkVersion`, network-security config, `org.gradle.java.home` pin or Firebase
    manifest entries: none apply to the signer.
12. Line-ending rewrites of `signer.js` and data files: `.gitattributes` + the pinned-digest test (§7.5).

---

## 13. Out of scope (iOS-only)

- Xcode project settings and their Android stand-ins: `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` map to
  `versionName` / `versionCode` (§3.2). `DEVELOPMENT_TEAM`, automatic signing, the shared Archive scheme,
  `TARGETED_DEVICE_FAMILY`, `IPHONEOS_DEPLOYMENT_TARGET = 17.0`, `UILaunchStoryboardName` (→ splash theme),
  `UIApplicationSceneManifest` and `UIStatusBarStyle` (`IOS:BitToolSigner.xcodeproj/project.pbxproj:414-442`).
- `INFOPLIST_KEY_ITSAppUsesNonExemptEncryption = NO` and the App Store export questionnaire
  (`IOS:docs/Export-Compliance.md`). Play has no such declaration. The technical basis (signatures and hashing
  only; the only cipher is the OS's AES-GCM) carries over only if Android encrypts the vault with the platform's
  JCA / Keystore AES-GCM and bundles no cipher library (no BouncyCastle). Keep it that way (`vault-crypto.md`).
- `InfoPlist.strings` / `NSFaceIDUsageDescription`, Face ID / Touch ID names in strings (`"%@ Lock"` with
  `biometry.name`, the footer `"Require %@ to open BitTool Signer at launch and after 2 minutes in the background. Every signature still needs the wallet’s password."`,
  `IOS:…/SettingsTab.swift:23-42`). These are replaced by Android keys (`activity-settings.md` §3.1, §12.3).
- `PrivacyInfo.xcprivacy`; the App Store / TestFlight upload checklist (`IOS:release/Xcode-Upload-Checklist.md`);
  App Store Connect records.
- FairPlay, Apple Configurator `.ipa` extraction and the "binary cannot be reproduced" argument
  (`IOS:docs/Reproducible-Build.md`, steps 1–2). On Android the APK is not encrypted: users pull `base.apk` with
  `adb` and hash `assets/engine/signer.js` directly (§7.2 step 7).
- `scripts/write-localizations.py` writing `.lproj/Localizable.strings` / `InfoPlist.strings`. Its validation rules
  are reused (§8.3); its output format is not.
- The simulator UI-test context (`SimulatorUITestContext`, `IOS:BitToolSigner/BitToolSignerApp.swift:14-23`), Liquid
  Glass, and iOS 17/18 fallbacks.
- Ownbit-only items that also do not apply to the signer: in-app APK updater, `REQUEST_INSTALL_PACKAGES`, update
  FileProvider, "Check for updates" / "Rate on Google Play" rows, Firebase/FCM, network security config, server
  secrets, NDK stripping, deep-link intent filters, portrait lock, Room convention plugin, ML Kit, WebView JS host,
  jitpack repository.
