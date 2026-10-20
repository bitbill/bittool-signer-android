package com.bittool.signer.core.model.util

import java.nio.CharBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Swift `String` semantics the port depends on (models-state.md §0.3, vault-crypto.md §17.10). */
class TextUtilsTest {
    @Test
    fun graphemesCountUserPerceivedCharacters() {
        assertEquals(0, Graphemes.count(""))
        assertEquals(5, Graphemes.count("abcde"))
        assertEquals(1, Graphemes.count("👍🏽"))
        assertEquals(1, Graphemes.count("👩‍👩‍👧"))
        assertEquals(1, Graphemes.count("é"))
        assertEquals(1, Graphemes.count("\r\n"))
        assertEquals(2, Graphemes.count("が。"))
        assertEquals(3, Graphemes.count(CharBuffer.wrap("a👍🏽b".toCharArray())))
    }

    @Test
    fun prefixAndSuffixCutOnClusterBoundaries() {
        assertEquals("ab", Graphemes.prefix("abc", 2))
        assertEquals("a👍🏽", Graphemes.prefix("a👍🏽b", 2))
        assertEquals("👍🏽b", Graphemes.suffix("a👍🏽b", 2))
        assertEquals("abc", Graphemes.prefix("abc", 10))
        assertEquals("abc", Graphemes.suffix("abc", 10))
        assertEquals("", Graphemes.prefix("abc", 0))
        assertEquals("W", Graphemes.first("Wallet"))
        assertNull(Graphemes.first(""))
        assertTrue(Graphemes.exceeds("a".repeat(301), 300))
        assertFalse(Graphemes.exceeds("a".repeat(300), 300))
        assertFalse(Graphemes.exceeds("👍🏽".repeat(300), 300))
    }

    @Test
    fun hasPrefixIsCharacterWiseLikeSwift() {
        // NFKD keys: "が" is "か" + U+3099, one character. A typed "か" is not its prefix (vault-crypto.md §13.2).
        val ga = java.text.Normalizer.normalize("がいこく", java.text.Normalizer.Form.NFKD)
        val ka = java.text.Normalizer.normalize("か", java.text.Normalizer.Form.NFKD)
        assertFalse(Graphemes.hasPrefix(ga, ka))
        assertTrue(Graphemes.hasPrefix("abandon", "aba"))
        assertTrue(Graphemes.hasPrefix("abandon", ""))
        assertFalse(Graphemes.hasPrefix("ab", "abc"))
        // Korean: "가" (L V) is not a prefix of "각" (L V T) once decomposed.
        val gak = java.text.Normalizer.normalize("각", java.text.Normalizer.Form.NFKD)
        val ga2 = java.text.Normalizer.normalize("가", java.text.Normalizer.Form.NFKD)
        assertFalse(Graphemes.hasPrefix(gak, ga2))
    }

    @Test
    fun utf8SizeMatchesTheEncoder() {
        for (text in listOf("", "abc", "é", "日本", "👋 世界 é", "\u0000", "a\uD800b")) {
            assertEquals(text.encodeToByteArray().size, text.utf8Size(), text)
        }
        assertEquals(2, "é".utf8Size())
        assertEquals(4, "😀".utf8Size())
    }

    @Test
    fun utf8BytesOfCharsMatchesTheEncoder() {
        for (text in listOf("Test-Password1", "pässwörd", "👍🏽 パスワード", "a\uD800b")) {
            assertContentEquals(text.encodeToByteArray(), utf8Bytes(text.toCharArray()), text)
        }
    }

    @Test
    fun swiftWhitespaceSets() {
        assertEquals("a b", "　 a b\t\n\u0085".trimWhitespacesAndNewlines())
        assertEquals("a\n", " a\n".trimWhitespaces())
        assertTrue(isUnicodeWhiteSpace(0x3000))
        assertTrue(isUnicodeWhiteSpace(0x85))
        assertFalse(isUnicodeWhiteSpace(0x1C))
        assertTrue(" \n\t".isBlankSwift())
        assertFalse(" x ".isBlankSwift())
    }

    @Test
    fun hexRoundTripsAndRejectsGarbage() {
        val bytes = byteArrayOf(0, 1, 0x7F, -1, -0x80)
        assertEquals("00017fff80", Hex.encode(bytes))
        assertContentEquals(bytes, Hex.decode("00017FFF80"))
        assertNull(Hex.decode("abc"))
        assertNull(Hex.decode("zz"))
        assertTrue(Hex.isHex("73c5da0a", 4))
        assertTrue(Hex.isHex("73C5DA0A", 4))
        assertFalse(Hex.isHex("73c5da0", 4))
        assertFalse(Hex.isHex("", 0))
        assertTrue(Hex.isHash64("a".repeat(64)))
        assertFalse(Hex.isHash64("g".repeat(64)))
    }

    @Test
    fun wipingZeroesAndConstantTimeCompares() {
        val chars = "secret".toCharArray()
        chars.useAndWipe { assertEquals('s', it[0]) }
        assertTrue(chars.all { it == '\u0000' })
        assertTrue(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(constantTimeEquals("ab".toCharArray(), "abc".toCharArray()))
    }
}
