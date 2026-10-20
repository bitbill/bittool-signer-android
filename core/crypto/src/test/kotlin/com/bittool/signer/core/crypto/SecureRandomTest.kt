package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.vault.VaultError
import java.security.SecureRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** Ports `SecurityTests.nativeEntropyIsBoundedAndUnique` (`BitToolSignerTests/SecurityTests.swift:22-31`). */
class SecureRandomTest {
    private val random = SystemSecureRandomSource()

    @Test
    fun nativeEntropyIsBoundedAndUnique() {
        val first = random.bytes(32)
        val second = random.bytes(32)
        assertEquals(32, first.size)
        assertFalse(first.contentEquals(second))
        assertEquals(32, random.entropyHex(12).size)
        assertEquals(64, random.entropyHex(24).size)
        assertFailsWith<VaultError.RandomFailure> { random.bytes(65_537) }
        assertFailsWith<VaultError.RandomFailure> { random.bytes(0) }
        assertFailsWith<VaultError.InvalidSecret> { random.entropyHex(13) }
    }

    @Test
    fun entropyIsLowercaseHexOfWordCountOverThreeTimesFourBytes() {
        for ((words, bytes) in listOf(12 to 16, 15 to 20, 18 to 24, 21 to 28, 24 to 32)) {
            val hex = random.entropyHex(words)
            assertEquals(bytes * 2, hex.size)
            assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
        }
        assertEquals(65_536, random.bytes(65_536).size)
    }

    @Test
    fun aFailingProviderIsARandomFailure() {
        val broken = SystemSecureRandomSource(
            object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) = throw IllegalStateException("no entropy")
            },
        )
        assertFailsWith<VaultError.RandomFailure> { broken.bytes(16) }
        assertFailsWith<VaultError.RandomFailure> { broken.entropyHex(12) }
    }

    @Test
    fun theKotlinRandomViewDrawsFromTheSameSource() {
        val seeded = SystemSecureRandomSource(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1) })
        val again = SystemSecureRandomSource(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1) })
        assertContentEquals(seeded.asKotlinRandom().nextBytes(8), again.asKotlinRandom().nextBytes(8))
    }
}
