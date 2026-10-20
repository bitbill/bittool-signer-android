import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.bittool.android.application)
    alias(libs.plugins.bittool.hilt)
    alias(libs.plugins.kotlin.serialization)
}

val secrets = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String = (secrets.getProperty(key) ?: System.getenv(key.uppercase().replace('.', '_')) ?: "").trim()

/**
 * Version. versionCode = MAJOR·1 000 000 + MINOR·10 000 + PATCH·100 + BUILD, where BUILD (0–99,
 * `-Psigner.buildNumber=N`, default 1 = iOS CURRENT_PROJECT_VERSION) re-issues the same versionName (e.g. a rejected store upload).
 * 1.0.0 (1) → 1000001; 1.0.1 (1) → 1000101; 1.1.0 (1) → 1010001. Kept in step with the iOS MARKETING_VERSION.
 */
val signerVersionName = "1.0.0"
val signerBuildNumber = providers.gradleProperty("signer.buildNumber").orNull?.trim()?.toInt() ?: 1
val signerVersionCode: Int = signerVersionName.split('.').map(String::toInt).let { (major, minor, patch) ->
    require(minor in 0..99 && patch in 0..99 && signerBuildNumber in 0..99) {
        "versionName $signerVersionName / signer.buildNumber $signerBuildNumber out of range (0–99 each)"
    }
    major * 1_000_000 + minor * 10_000 + patch * 100 + signerBuildNumber
}

/**
 * Release signing (docs/release.md). The release key comes from local.properties (never committed) or the environment:
 * `signer.release.storeFile` (path relative to the repo root, or absolute), `signer.release.storePassword`,
 * `signer.release.keyAlias`, `signer.release.keyPassword` (env: SIGNER_RELEASE_STOREFILE, …).
 * Without them the release build is unsigned, or debug-signed with `-Psigner.allowDebugSigning=true`
 * (local device testing only — never distribute such a build).
 */
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .associateWith { secret("signer.release.$it") }
val hasReleaseKey = releaseSigningKeys.values.all { it.isNotEmpty() }
val missingReleaseKeys = releaseSigningKeys.filterValues { it.isEmpty() }.keys
val allowDebugSigning = providers.gradleProperty("signer.allowDebugSigning").orNull.toBoolean()

/**
 * Optional Google Play upload key: `signer.upload.storeFile` / `storePassword` / `keyAlias` / `keyPassword`
 * (env SIGNER_UPLOAD_*). When all four are set only the `play` release (the .aab uploaded to Play) is signed with it;
 * the `apk` distribution keeps the release key, whose certificate fingerprint is what users verify.
 */
val uploadSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .associateWith { secret("signer.upload.$it") }
val hasUploadKey = uploadSigningKeys.values.all { it.isNotEmpty() }
val missingUploadKeys = uploadSigningKeys.filterValues { it.isEmpty() }.keys

android {
    namespace = "com.bittool.signer"

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseSigningKeys.getValue("storeFile"))
                storePassword = releaseSigningKeys.getValue("storePassword")
                keyAlias = releaseSigningKeys.getValue("keyAlias")
                keyPassword = releaseSigningKeys.getValue("keyPassword")
            }
        }
        if (hasUploadKey) {
            create("upload") {
                storeFile = rootProject.file(uploadSigningKeys.getValue("storeFile"))
                storePassword = uploadSigningKeys.getValue("storePassword")
                keyAlias = uploadSigningKeys.getValue("keyAlias")
                keyPassword = uploadSigningKeys.getValue("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.bittool.signer"
        versionCode = signerVersionCode
        versionName = signerVersionName
        // The engine library is built for these ABIs only (no 32-bit x86): ship no other ABI so nothing loads half a set.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        buildConfigField("String", "REPOSITORY_URL", "\"https://github.com/bitbill/bittool-signer-android\"")
    }

    androidResources {
        // The 21 interface languages only (plus the regional/legacy codes Android resolves them through).
        localeFilters += listOf(
            "en", "ar", "de", "es", "b+es+419", "fil", "b+fil", "tl", "fr", "in", "b+id", "it", "ja", "ko", "pl",
            "pt", "pt-rBR", "pt-rPT", "ru", "th", "tr", "uk", "vi", "b+zh+Hans", "b+zh+Hant", "zh-rCN", "zh-rTW", "zh-rHK",
        )
        generateLocaleConfig = false
        noCompress += listOf("js", "pack")
    }

    /**
     * Distribution. `apk` = direct download from bittool.com/signer (About shows the signing-certificate SHA-256 users
     * compare before installing); `play` = Google Play (App Bundle). Neither has any network code: the signer declares no
     * INTERNET permission in either flavor, and both ship the byte-identical engine. Same applicationId for both.
     */
    flavorDimensions += "distribution"
    productFlavors {
        create("apk") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("String", "DISTRIBUTION", "\"apk\"")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                hasReleaseKey -> signingConfigs.getByName("release")
                allowDebugSigning -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }

    bundle {
        // In-app language switching needs every language in the base APK.
        language { enableSplit = false }
        abi { enableSplit = true }
        density { enableSplit = true }
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    lint {
        checkDependencies = true
    }

    testOptions {
        unitTests.all { test ->
            // Roborazzi screenshots of the shell (src/test/.../screenshot) record PNGs into app/build/outputs/roborazzi/
            // for visual QA (no goldens are committed). `-Psigner.screenshots=false` skips them on a slow machine.
            test.systemProperty("roborazzi.test.record", "true")
            if (project.findProperty("signer.screenshots")?.toString() == "false") test.exclude("**/screenshot/**")
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release").withFlavor("distribution" to "play")) { variant ->
        if (hasUploadKey) variant.signingConfig.setConfig(android.signingConfigs.getByName("upload"))
    }
}

// Release sanity checks. Plain values are copied into locals so the configuration cache never stores the script.
tasks.matching { it.name.matches(Regex("pre(Apk|Play)ReleaseBuild")) }.configureEach {
    val isPlay = name.contains("Play")
    val partialRelease = if (!hasReleaseKey && missingReleaseKeys.size < releaseSigningKeys.size) {
        missingReleaseKeys.joinToString { "signer.release.$it" }
    } else null
    val partialUpload = if (!hasUploadKey && missingUploadKeys.size < uploadSigningKeys.size) {
        missingUploadKeys.joinToString { "signer.upload.$it" }
    } else null
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

/**
 * The merged manifest of every variant must request exactly the gaps.md G8 permission set (DECISIONS §6: no network
 * permission; "the OS refuses every socket" is enforced by the build, not only by `tools:node="remove"`): a library
 * added later cannot slip in a permission unnoticed, and a missing one (e.g. HIDE_OVERLAY_WINDOWS) fails as well.
 * The report lists what the variant requests (`build/reports/permissions/<variant>.txt`).
 */
abstract class CheckMergedPermissions : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val expected: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val xml = mergedManifest.get().asFile.readText()
        val requested = Regex("""<uses-permission(?:-sdk-23)?\b[^>]*?android:name="([^"]+)"""")
            .findAll(xml).map { it.groupValues[1] }.toSortedSet()
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(requested.joinToString("\n", postfix = "\n"))
        val allowed = expected.get().toSortedSet()
        if (requested != allowed) {
            throw GradleException(
                "Merged manifest permissions differ from the gaps.md G8 set. Unexpected: ${requested - allowed}; " +
                    "missing: ${allowed - requested}.",
            )
        }
    }
}

/**
 * Release sanity after R8 (ARCHITECTURE §3 W2-shell): the debug UI-test harness (`app/src/debug`) must not exist in a
 * release build, neither as a class in the R8 mapping nor as its intent-extra keys in the dex.
 */
abstract class CheckReleaseHasNoHarness : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkDirectory: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val forbidden = listOf("DebugUiTestHarness", "FixtureDeviceKeyStore", "FixtureBiometricAuthenticator", "DebugUiTestModule")
        val mappingText = mapping.get().asFile.readText()
        val inMapping = forbidden.filter { name -> Regex("""(?m)^\S*\b$name\b\S* -> """).containsMatchIn(mappingText) }
        val apks = apkDirectory.get().asFile.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        if (apks.isEmpty()) throw GradleException("No APK found in ${apkDirectory.get().asFile}")
        val marker = "bts.uitest.".toByteArray(Charsets.US_ASCII)
        val dexName = Regex("""classes\d*\.dex""")
        val inDex = apks.flatMap { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { dexName.matches(it.name) }
                    .filter { entry -> zip.getInputStream(entry).use { it.readBytes() }.contains(marker) }
                    .map { "${apk.name}!${it.name}" }
                    .toList()
            }
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(
            "harness classes in the R8 mapping: $inMapping\nharness intent extras in the dex: $inDex\n",
        )
        if (inMapping.isNotEmpty() || inDex.isNotEmpty()) {
            throw GradleException("The debug UI-test harness reached a release build: classes $inMapping, dex $inDex")
        }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar(Char::uppercase)
        val permissions = tasks.register<CheckMergedPermissions>("check${cap}Permissions") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            // gaps.md G8: scanning, App Lock (androidx.biometric adds USE_FINGERPRINT for API < 28), the screenshot and
            // screen-recording callbacks (API 34 / 35), hiding foreign overlays (G4), and androidx.core's signature
            // permission for RECEIVER_NOT_EXPORTED receivers below API 33.
            expected.addAll(
                "android.permission.CAMERA",
                "android.permission.USE_BIOMETRIC",
                "android.permission.USE_FINGERPRINT",
                "android.permission.DETECT_SCREEN_CAPTURE",
                "android.permission.DETECT_SCREEN_RECORDING",
                "android.permission.HIDE_OVERLAY_WINDOWS",
            )
            expected.add(variant.applicationId.map { "$it.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" })
            report.set(layout.buildDirectory.file("reports/permissions/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$cap" || it.name == "bundle$cap" }.configureEach { dependsOn(permissions) }

        if (variant.buildType == "release") {
            val harness = tasks.register<CheckReleaseHasNoHarness>("check${cap}HasNoHarness") {
                mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
                apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                report.set(layout.buildDirectory.file("reports/release/${variant.name}-harness.txt"))
            }
            tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(harness) }
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.l10n)
    implementation(projects.core.designsystem)
    implementation(projects.core.engine)
    implementation(projects.core.crypto)
    implementation(projects.core.data)
    implementation(projects.core.security)
    implementation(projects.core.scanner)
    implementation(projects.core.ui)
    implementation(projects.core.navigation)

    implementation(projects.feature.onboarding)
    implementation(projects.feature.setup)
    implementation(projects.feature.wallets)
    implementation(projects.feature.sign)
    implementation(projects.feature.activity)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
    // Not in the version catalogue (DECISIONS §8): record-only screenshots, the setup :core:designsystem uses.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
