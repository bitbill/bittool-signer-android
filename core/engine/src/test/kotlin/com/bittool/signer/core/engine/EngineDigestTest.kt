package com.bittool.signer.core.engine

import com.bittool.signer.core.engine.internal.EngineBundle
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shipped engine is byte-identical to the iOS release (engine/bundle-manifest.json of BitTool Signer 1.0.0):
 * anyone can unzip an APK and compare `assets/engine/signer.js` with a reproducible engine build.
 */
class EngineDigestTest {
    private val asset = File("src/main/assets/${EngineBundle.ASSET_PATH}")

    @Test
    fun engineAssetMatchesThePinnedDigest() {
        val bytes = asset.readBytes()
        assertEquals(1_181_026, bytes.size)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(EngineBundle.EXPECTED_SHA256, digest)
    }

    @Test
    fun jniSafeSourceEscapesNulAndSupplementaryCharacters() {
        assertEquals("/[\\x00-\\x08]/", EngineBundle.jniSafeSource("/[\u0000-\\x08]/"))
        assertEquals("'\\x00'", EngineBundle.jniSafeSource("'\\\u0000'"))
        assertEquals("'\\uD83D\\uDE00'", EngineBundle.jniSafeSource("'😀'"))
        assertEquals("plain ascii", EngineBundle.jniSafeSource("plain ascii"))
    }

    @Test
    fun theShippedEngineHasExactlyOneCharacterThatNeedsEscaping() {
        val source = asset.readText(Charsets.UTF_8)
        assertEquals(1, source.count { it == '\u0000' || it.isSurrogate() })
    }
}
