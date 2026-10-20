package com.bittool.signer.identity

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.bittool.signer.core.common.log.Log
import java.security.MessageDigest

/**
 * The SHA-256 of the certificate Android verified when it installed this APK (activity-settings.md §14.8): what
 * `apksigner verify --print-certs` prints as "certificate SHA-256 digest", for the Open Source › This build "Signing
 * certificate" row. For the `apk` flavor users compare it with the fingerprint published at bittool.com/signer; for
 * `play` it is the Play App Signing certificate. Public data (the certificate is in every APK).
 */
object SigningCertificate {
    private const val TAG = "SigningCertificate"

    /** The first current signer's digest as uppercase colon-separated hex (`AB:CD:…`, `BuildInfo` KDoc), or null. */
    fun sha256(context: Context): String? = try {
        signatures(context).firstOrNull()?.let { format(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, e) { "Own package not found" }
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, e) { "Signing certificate unavailable" }
        null
    }

    /**
     * `AB:CD:…` (uppercase, colon-separated), the format `keytool` and the Play Console print. Built by hand: a
     * default-locale `String.format` would write Arabic-Indic digits in an Arabic interface.
     */
    fun format(digest: ByteArray): String = digest.joinToString(":") { byte ->
        val value = byte.toInt() and 0xFF
        "${HEX[value shr 4]}${HEX[value and 0x0F]}"
    }

    private const val HEX = "0123456789ABCDEF"

    @Suppress("DEPRECATION")
    private fun signatures(context: Context): List<Signature> {
        val manager = context.packageManager
        val name = context.packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            // The current signer(s), also after a v3 key rotation (not the original certificate of the lineage).
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            manager.getPackageInfo(name, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
        }
    }
}
