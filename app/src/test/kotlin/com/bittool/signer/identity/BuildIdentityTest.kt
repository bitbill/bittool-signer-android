package com.bittool.signer.identity

import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.BuildConfig
import com.bittool.signer.core.common.Distribution
import com.bittool.signer.di.AppModule
import com.bittool.signer.di.DistributionModule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The build facts Settings shows (activity-settings.md §3.4, §14.8; DECISIONS §1, §10). */
@RunWith(RobolectricTestRunner::class)
class BuildIdentityTest {
    @Test
    fun theDistributionComesFromTheFlavorsModule() {
        assertEquals(Distribution.fromFlavor(BuildConfig.DISTRIBUTION), DistributionModule.distribution())
    }

    @Test
    fun theVersionReadsLikeIos() {
        val info = AppModule.buildInfo(ApplicationProvider.getApplicationContext(), DistributionModule.distribution())
        // iOS shows "1.0.0 (1)": the build number defaults to 1 (DECISIONS §10).
        assertEquals("1.0.0 (1)", info.displayVersion)
        assertEquals("https://github.com/bitbill/bittool-signer-android", info.repositoryUrl)
        assertEquals(BuildConfig.DEBUG, info.isDebug)
        assertTrue(AppModule.engineTestOperations() == BuildConfig.DEBUG)
    }

    @Test
    fun theCertificateDigestIsUppercaseColonSeparatedHex() {
        assertEquals("00:0A:FF:7F", SigningCertificate.format(byteArrayOf(0x00, 0x0A, 0xFF.toByte(), 0x7F)))
        val digest = SigningCertificate.format(ByteArray(32) { it.toByte() })
        assertTrue(Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$").matches(digest))
    }
}
