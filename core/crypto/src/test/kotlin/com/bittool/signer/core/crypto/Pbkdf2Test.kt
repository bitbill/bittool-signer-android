package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.kdf.Pbkdf2
import com.bittool.signer.core.crypto.kdf.Pbkdf2HmacSha256
import com.bittool.signer.core.crypto.kdf.Sha256
import com.bittool.signer.core.model.util.Hex
import java.security.MessageDigest
import java.util.Random
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

/**
 * The vault KDF and its SHA-256 (ports `SecurityTests.passwordDerivationMatchesIndependentVector`,
 * `BitToolSignerTests/SecurityTests.swift:33-37`), plus published vectors and a JCA cross-check. On the JVM the native
 * library is absent, so [Pbkdf2.hmacSha256] runs the Kotlin implementation; the instrumented benchmark checks the
 * native one against the same vector.
 */
class Pbkdf2Test {
    @Test
    fun passwordDerivationMatchesIndependentVector() {
        val key = Pbkdf2.hmacSha256("Test-Password1".encodeToByteArray(), ByteArray(32) { it.toByte() }, 600_000, 32)
        assertEquals("05b079f15d09a3589c02ca352a3dd38ad23daf4663a59d6072c2333ddb4901d9", Hex.encode(key))
        assertEquals("kotlin", Pbkdf2.sha256Implementation)
    }

    @Test
    fun rfc7914PbkdfVectors() {
        // RFC 7914 §11: PBKDF2-HMAC-SHA256, 64-byte outputs (two blocks: exercises the block counter).
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            Hex.encode(Pbkdf2HmacSha256.derive("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64)),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            Hex.encode(Pbkdf2HmacSha256.derive("Password".encodeToByteArray(), "NaCl".encodeToByteArray(), 80_000, 64)),
        )
    }

    @Test
    fun agreesWithTheJcaImplementationForEveryKeyAndSaltShape() {
        val random = Random(7)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        // ASCII passwords (JCA encodes the char[] itself), lengths around the 64-byte HMAC block and long salts.
        for (passwordLength in listOf(1, 8, 63, 64, 65, 100, 200)) {
            for (saltLength in listOf(0, 1, 32, 55, 56, 64, 120)) {
                val password = CharArray(passwordLength) { ('!'.code + random.nextInt(94)).toChar() }
                val salt = ByteArray(saltLength).also(random::nextBytes)
                val keyLength = listOf(16, 32, 33, 64)[random.nextInt(4)]
                val iterations = 1 + random.nextInt(300)
                val expected = if (saltLength == 0) null else factory.generateSecret(PBEKeySpec(password, salt, iterations, keyLength * 8)).encoded
                val actual = Pbkdf2HmacSha256.derive(String(password).encodeToByteArray(), salt, iterations, keyLength)
                if (expected != null) assertEquals(Hex.encode(expected), Hex.encode(actual), "p=$passwordLength s=$saltLength i=$iterations")
            }
        }
    }

    @Test
    fun sha256MatchesNistVectorsAndMessageDigest() {
        fun digest(bytes: ByteArray) = Sha256().run {
            update(bytes)
            finish()
            Hex.encode(digestBytes())
        }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest("abc".encodeToByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
        val random = Random(11)
        val reference = MessageDigest.getInstance("SHA-256")
        for (length in 0..300) {
            val data = ByteArray(length).also(random::nextBytes)
            // Split updates exercise the pending-block path.
            val split = Sha256().run {
                val cut = if (length == 0) 0 else random.nextInt(length)
                update(data, 0, cut)
                update(data, cut, length - cut)
                finish()
                Hex.encode(digestBytes())
            }
            assertEquals(Hex.encode(reference.digest(data)), split, "length $length")
        }
    }

    @Test
    fun hmacSha512PbkdfMatchesTheJcaImplementation() {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val salt = "mnemonicTREZOR".encodeToByteArray()
        val expected = factory.generateSecret(PBEKeySpec(ABANDON_PHRASE.toCharArray(), salt, 2048, 512)).encoded
        assertEquals(Hex.encode(expected), Hex.encode(Pbkdf2.hmacSha512(ABANDON_PHRASE.encodeToByteArray(), salt, 2048, 64)))
        // Two output blocks.
        val long = factory.generateSecret(PBEKeySpec("p".toCharArray(), "s".encodeToByteArray(), 3, 1024)).encoded
        assertEquals(Hex.encode(long), Hex.encode(Pbkdf2.hmacSha512("p".encodeToByteArray(), "s".encodeToByteArray(), 3, 128)))
    }

    @Test
    fun aCancelledDerivationStopsAtTheNextCheckpoint() {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            Pbkdf2HmacSha256.derive("x".encodeToByteArray(), ByteArray(32), 600_000, 32) {
                if (++calls == 3) throw IllegalStateException("cancelled")
            }
        }
        assertEquals(3, calls)
    }
}
