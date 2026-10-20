package com.bittool.signer.core.data.reference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.util.Hex
import java.security.MessageDigest
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * gaps.md G8: `:core:data`'s bundled data files are byte-identical with the iOS app's `BitToolSigner/Resources`
 * (verified on 2026-10-20), and this pins them so an accidental edit or re-encoding (line endings, a JSON
 * re-formatter, a lossy PNG pass) fails here instead of drifting from the engine's embedded copies. A deliberate data
 * update changes the iOS resource and these digests together.
 */
@RunWith(RobolectricTestRunner::class)
class BundledAssetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun bytes(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test
    fun theReferenceDataIsTheIosResources() {
        val expected = mapOf(
            AssetReferenceDataRepository.TOKEN_REGISTRY to "e1b00e44c10455931b302a226dd3576203d080b9384e67c08bb37b550634aaaa",
            AssetReferenceDataRepository.KNOWN_CONTRACTS to "de22a4b43cb34f4f8c6093ea8d7d41bf80e2a8f4278a83272be7522c06bc34e6",
            PackTokenIconSource.INDEX to "e3bf532e4707ad6fe68d784c8ba1cb0eae30f167a4b190e4330b255233d033cf",
            PackTokenIconSource.PACK to "6f4ad9321f7c219db32539b79ccacc2e4f8943fc0e775b0224814149a1b226ab",
        )
        for ((path, digest) in expected) assertEquals(digest, sha256(bytes(path)), path)
        // models-state.md §11: the shipped pack and index sizes.
        assertEquals(11_205_605, bytes(PackTokenIconSource.PACK).size)
        assertEquals(67_531, bytes(PackTokenIconSource.INDEX).size)
    }

    @Test
    fun theDefaultLegalTextsAndEngineNoticesAreTheIosResources() {
        // The flavors replace the two legal texts in `:app` (activity-settings.md §10); these are the shared defaults.
        val expected = mapOf(
            LegalDocument.Terms.assetPath to "8ec76fde215768d6fbd4619aeb7ec43b173743b161681f053a7ba83f7a58acca",
            LegalDocument.Privacy.assetPath to "f7b0693a8d7c01e869aa897c412049a3140e0a9abc7daba487dfd35bcbd7933b",
            AssetLegalDocumentRepository.NOTICE_FILES.first() to "e294278f764ad77e73e5f4c872891c489e3b42403e41a6e416dd257303bbc581",
        )
        for ((path, digest) in expected) assertEquals(digest, sha256(bytes(path)), path)
    }
}
