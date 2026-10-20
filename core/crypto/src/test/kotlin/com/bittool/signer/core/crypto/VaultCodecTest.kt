package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.vault.SecretJson
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.crypto.vault.VaultFormat
import com.bittool.signer.core.model.json.canonicalJson
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/** The vault's byte-level codecs: the secret JSON built without `String`s and the `createdAt` number. */
class VaultCodecTest {
    private fun roundTrip(mnemonic: String, passphrase: String) {
        val bytes = SecretJson.encode(mnemonic.toCharArray(), passphrase.toCharArray())
        // Byte-identical to sorted-key JSON of the same values (iOS `JSONEncoder` `.sortedKeys`, minus `\/`).
        val expected = JsonObject(mapOf("mnemonic" to JsonPrimitive(mnemonic), "passphrase" to JsonPrimitive(passphrase))).canonicalJson()
        assertEquals(expected, bytes.decodeToString())
        SecretJson.decode(bytes).use {
            assertEquals(mnemonic, String(it.mnemonic.chars()))
            assertEquals(passphrase, String(it.passphrase.chars()))
        }
    }

    @Test
    fun theSecretJsonRoundTripsEveryCharacter() {
        roundTrip(ABANDON_PHRASE, "")
        roundTrip("的 一 是", "Ünïcødé passphrase 你好 👩‍👩‍👧 \"quoted\" back\\slash")
        roundTrip("tab\tnew\nline\rcr\u0000nul\u001F\u000C\b", "/")
        roundTrip("あいこくしん　あいこくしん", "㍍ガバヴァぱばぐゞちぢ十人十色")
    }

    @Test
    fun decodingAcceptsAnyValidJsonOfTheSameShape() {
        val text = " {\n \"passphrase\" : \"a\\/b\\u00e9\\ud83d\\ude00\",\t\"mnemonic\":\"x\"} "
        SecretJson.decode(text.encodeToByteArray()).use {
            assertEquals("x", String(it.mnemonic.chars()))
            assertEquals("a/bé😀", String(it.passphrase.chars()))
        }
    }

    @Test
    fun decodingIsStrict() {
        val bad = listOf(
            """{"mnemonic":"x"}""",
            """{"mnemonic":"x","passphrase":"y","extra":"z"}""",
            """{"mnemonic":"x","mnemonic":"y","passphrase":""}""",
            """{"mnemonic":"x","passphrase":""} trailing""",
            """{"mnemonic":"x","passphrase":1}""",
            """{"mnemonic":"x" "passphrase":""}""",
            """{"mnemonic":"x\q","passphrase":""}""",
            """{"mnemonic":"x""",
            "",
        )
        for (text in bad) assertFailsWith<VaultError.AuthenticationFailed>(text) { SecretJson.decode(text.encodeToByteArray()) }
        // Overlong UTF-8, a raw control character, and an encoded surrogate are not valid JSON text.
        val prefix = """{"passphrase":"","mnemonic":"""".encodeToByteArray()
        for (raw in listOf(byteArrayOf(0xC0.toByte(), 0xAF.toByte()), byteArrayOf(0x0A), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))) {
            assertFailsWith<VaultError.AuthenticationFailed> { SecretJson.decode(prefix + raw + "\"}".encodeToByteArray()) }
        }
    }

    @Test
    fun aLoneSurrogateIsNeverEncoded() {
        assertFailsWith<VaultError.InvalidSecret> { SecretJson.encode("a\uD800".toCharArray(), CharArray(0)) }
    }

    @Test
    fun createdAtIsSecondsSince2001WithMillisecondPrecision() {
        assertEquals("781692800.123", VaultFormat.formatReferenceSeconds(1_760_000_000_123L))
        assertEquals("781692800", VaultFormat.formatReferenceSeconds(1_760_000_000_000L))
        assertEquals("781692800.1", VaultFormat.formatReferenceSeconds(1_760_000_000_100L))
        assertEquals("0", VaultFormat.formatReferenceSeconds(978_307_200_000L))
        assertEquals("-0.001", VaultFormat.formatReferenceSeconds(978_307_199_999L))
        for (millis in listOf(0L, 1L, 978_307_200_000L, 1_700_000_000_123L, 1_760_000_000_999L, 4_102_444_800_000L)) {
            assertEquals(millis, VaultFormat.parseReferenceSeconds(VaultFormat.formatReferenceSeconds(millis)))
        }
        // iOS-style doubles and exponents read too; absurd magnitudes are refused without expanding them.
        assertEquals(1_700_000_000_123L, VaultFormat.parseReferenceSeconds("721692800.123"))
        assertEquals(1_760_000_000_000L, VaultFormat.parseReferenceSeconds("7.816928E8"))
        assertNull(VaultFormat.parseReferenceSeconds("1e2000000000"))
        assertNull(VaultFormat.parseReferenceSeconds("1e-2000000000"))
        assertNull(VaultFormat.parseReferenceSeconds("true"))
    }
}
