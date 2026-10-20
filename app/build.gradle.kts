import java.util.Properties

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
 * `-Psigner.buildNumber=N`, default 0) re-issues the same versionName (e.g. a rejected store upload).
 * 1.0.0 → 1000000; 1.0.1 → 1000100; 1.1.0 → 1010000. Kept in step with the iOS MARKETING_VERSION.
 */
val signerVersionName = "1.0.0"
val signerBuildNumber = providers.gradleProperty("signer.buildNumber").orNull?.trim()?.toInt() ?: 0
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
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
