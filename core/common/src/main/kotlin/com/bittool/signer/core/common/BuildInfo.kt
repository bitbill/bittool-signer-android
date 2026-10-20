package com.bittool.signer.core.common

/**
 * Distribution channel = product flavor (DECISIONS.md §1). Both are offline and functionally identical; they differ
 * only in About/Open Source content, signing and packaging.
 */
enum class Distribution(val flavor: String) {
    /** Direct download from bittool.com/signer (`apk` flavor). */
    DirectApk("apk"),
    /** Google Play (`play` flavor, App Bundle). */
    GooglePlay("play");

    companion object {
        fun fromFlavor(flavor: String): Distribution = entries.firstOrNull { it.flavor == flavor } ?: DirectApk
    }
}

/**
 * Facts about this build, provided by `:app` from its `BuildConfig` (a Hilt `@Provides` in the app module; library
 * modules have no app `BuildConfig`). Shown in Settings › About ("1.0.0 (1)") and Open Source › This build.
 */
data class BuildInfo(
    val applicationId: String,
    /** `1.0.0` (debug builds add `-debug`). */
    val versionName: String,
    /** MAJOR·1 000 000 + MINOR·10 000 + PATCH·100 + BUILD (see `app/build.gradle.kts`). */
    val versionCode: Long,
    val distribution: Distribution,
    val isDebug: Boolean,
    /** Source repository shown on the Open Source page (`BuildConfig.REPOSITORY_URL`; owner decision). */
    val repositoryUrl: String,
    /**
     * SHA-256 of the APK signing certificate as uppercase colon-separated hex (`AB:CD:…`), for the Open Source › This
     * build "Signing certificate" row (activity-settings.md §14.8; for `apk` users compare it with bittool.com/signer,
     * for `play` it is the Play App Signing certificate). `:app`'s provider reads it once from `PackageManager`
     * (`GET_SIGNING_CERTIFICATES` / `signingInfo.apkContentsSigners` on API 28+, `GET_SIGNATURES` below; the first
     * signer); null when unavailable (the row then shows "Unavailable").
     */
    val signingCertificateSha256: String? = null,
) {
    /** The re-issue number of this version (versionCode % 100), the iOS `CFBundleVersion` analogue. */
    val buildNumber: Long get() = versionCode % 100

    /**
     * iOS `"\(short) (\(build))"` — Settings › About › Version, Open Source › App version and the rebuild step ("check
     * out the tag for version %@"), without the debug suffix. The build number defaults to 1 (`-Psigner.buildNumber`,
     * DECISIONS.md §10), so Android 1.0.0 shows "1.0.0 (1)" like iOS 1.0.0 (`CURRENT_PROJECT_VERSION = 1`); a re-issue
     * of the same version with `-Psigner.buildNumber=2` shows "1.0.0 (2)".
     */
    val displayVersion: String get() = "${versionName.removeSuffix("-debug")} ($buildNumber)"
}
