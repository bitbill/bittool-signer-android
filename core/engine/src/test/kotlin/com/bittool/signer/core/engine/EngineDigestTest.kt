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
        assertEquals(EngineBundle.EXPECTED_BYTES, bytes.size)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(EngineBundle.EXPECTED_SHA256, digest)
    }
}

class EngineExceptionTest {
    @Test
    fun boundsCodeAndMessageByGraphemes() {
        val e = EngineException.bounded("C".repeat(100), "👩‍👩‍👧".repeat(500))
        assertEquals(80, e.code.length)
        assertEquals("👩‍👩‍👧".repeat(400), e.message)
        val d = EngineException.bounded(null, null)
        assertEquals("SIGNING_FAILED", d.code)
        assertEquals("This request could not be signed.", d.message)
    }
}
