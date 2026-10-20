package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.bip39.Bip39
import com.bittool.signer.core.crypto.bip39.Bip39Candidates
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.crypto.bip39.Bip39Text
import com.bittool.signer.core.crypto.vault.VaultError
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The native pieces of iOS `HardeningTests` (`BitToolSignerTests/HardeningTests.swift:13-75`): seed vectors, the
 * wordlist and its suggestions, Standard SeedQR, the live draft. The engine parity cases
 * (`nativeSeedDerivesTheSameWalletAsTheEngine`, the engine `wordlist`) are instrumented (`EngineParityTest`). Also
 * the Swift-vs-Kotlin text pitfalls of vault-crypto.md §17.10 that the port must reproduce.
 */
class HardeningCryptoTest {
    private val bip39 = sharedBip39
    private val seed = Pbkdf2Bip39Seed()
    private fun seedHex(phrase: String, passphrase: String) = String(seed.hex(secret(phrase), secret(passphrase)))

    @Test
    fun nativeSeedMatchesPublishedBip39Vectors() {
        // Trezor BIP39 test vectors (passphrase "TREZOR").
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            seedHex(ABANDON_PHRASE, "TREZOR"),
        )
        assertEquals(
            "2e8905819b8723fe2c1d161860e5ee1830318dbf49a83bd451cfb8440c28bd6fa457fe1296106559a3c80937a1c1069be3a3a5bd381ee6260e8d9739fce1f607",
            seedHex("legal winner thank year wave sausage worth useful legal winner thank yellow", "TREZOR"),
        )
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            seedHex(ABANDON_PHRASE, ""),
        )
        // Whitespace and case are normalised exactly as the engine does.
        assertEquals(
            seedHex(ABANDON_PHRASE, "TREZOR"),
            seedHex("  ABANDON abandon\tabandon abandon abandon abandon abandon abandon abandon abandon abandon about ", "TREZOR"),
        )
        // NFKD: precomposed and decomposed passphrases are the same wallet.
        assertEquals(seedHex(ABANDON_PHRASE, "é"), seedHex(ABANDON_PHRASE, "é"))
        assertFailsWith<VaultError.InvalidSecret> { seed.hex(secret("   "), secret("")) }
    }

    @Test
    fun theSeedNormalisesNonAsciiPhrasesLikeSwift() {
        // Ideographic and no-break spaces separate words; fullwidth letters fold under NFKD; the passphrase is not trimmed.
        assertEquals(seedHex(ABANDON_PHRASE, ""), seedHex(ABANDON_PHRASE.replace(" ", "　"), ""))
        assertEquals(seedHex(ABANDON_PHRASE, ""), seedHex(ABANDON_PHRASE.replaceFirst("abandon", "ａbandon"), ""))
        assertEquals(seedHex(ABANDON_PHRASE, ""), seedHex(ABANDON_PHRASE.replace(" ", "  \n"), ""))
        assertFalse(seedHex(ABANDON_PHRASE, " TREZOR") == seedHex(ABANDON_PHRASE, "TREZOR"))
        // A lone surrogate cannot exist in Swift; it is refused instead of silently becoming "?".
        assertFailsWith<VaultError.InvalidSecret> { seed.hex(secret(ABANDON_PHRASE), secret("x\uD800")) }
        assertFailsWith<VaultError.InvalidSecret> { seed.hex(secret("abandon\uDC00"), secret("")) }
    }

    @Test
    fun bundledWordlistIsCanonical() {
        val list = bip39.wordlist(Bip39Language.English)
        assertTrue(list.isAvailable)
        assertEquals("abandon", list.words.first())
        assertEquals("zoo", list.words.last())
        assertEquals(listOf("abandon"), list.suggestions("aba"))
        assertTrue(list.suggestions("zz").isEmpty())
        assertEquals(listOf("abandon", "ability", "able"), list.suggestions("ab", limit = 3))
        assertTrue(list.suggestions("").isEmpty())
        assertTrue(list.isPrefix("ZO"))
    }

    @Test
    fun aListThatFailsItsPinnedHashIsUnavailableAndSkipped() {
        val tampered = com.bittool.signer.core.crypto.bip39.DefaultBip39 { language ->
            assetWordlists.read(language)?.let { bytes -> if (language == Bip39Language.English) bytes.copyOf().also { it[0] = 'b'.code.toByte() } else bytes }
        }
        val english = tampered.wordlist(Bip39Language.English)
        assertFalse(english.isAvailable)
        assertTrue(english.words.isEmpty())
        assertNull(english.index("abandon"))
        // Detection skips it: the English test phrase is no longer recognised, a Spanish one still is.
        assertFailsWith<com.bittool.signer.core.crypto.bip39.Bip39Error> { tampered.canonicalize(ABANDON_PHRASE) }
        assertNull(tampered.wordsFromSeedQr("0000".repeat(11) + "0003"))
        val missing = com.bittool.signer.core.crypto.bip39.DefaultBip39 { null }
        assertTrue(Bip39Language.entries.none { missing.wordlist(it).isAvailable })
        assertEquals(null, missing.draft("abandon").language)
    }

    @Test
    fun standardSeedQrDecodesAndRejectsMalformedDigits() {
        // Index 0 ("abandon") ×11 and 3 ("about").
        val digits = "0000".repeat(11) + "0003"
        assertEquals(ABANDON_PHRASE, bip39.wordsFromSeedQr(digits)?.joinToString(" "))
        assertEquals(ABANDON_PHRASE, bip39.wordsFromSeedQr(" \n$digits\n")?.joinToString(" "), "trimmed of white space and newlines")
        assertNull(bip39.wordsFromSeedQr(digits.dropLast(1)))
        assertNull(bip39.wordsFromSeedQr("2048".repeat(12)))
        assertNull(bip39.wordsFromSeedQr("abandon $digits"))
        assertNull(bip39.wordsFromSeedQr("０".repeat(48)), "fullwidth digits are not ASCII")
        assertEquals(24, bip39.wordsFromSeedQr("2047".repeat(24))?.size)
        assertEquals("zoo", bip39.wordsFromSeedQr("2047".repeat(12))?.last())
    }

    @Test
    fun phraseDraftReportsUnknownWordsButNotTheWordBeingTyped() {
        val typing = bip39.draft("abandon abandon ab")
        assertEquals("ab", typing.partialWord)
        assertTrue(typing.unknownWords.isEmpty())
        val wrong = bip39.draft("abandon xyzzy abandon ")
        assertNull(wrong.partialWord)
        assertEquals(listOf("xyzzy"), wrong.unknownWords)
        assertTrue(bip39.draft(ABANDON_PHRASE).hasSupportedLength)
        assertFalse(bip39.draft("abandon abandon").hasSupportedLength)
        // Twelve words whose last one is still a fragment are not shown as complete.
        val unfinished = ABANDON_PHRASE.split(" ").dropLast(1).joinToString(" ") + " ab"
        assertTrue(bip39.draft(unfinished).hasSupportedLength)
        assertFalse(bip39.draft(unfinished).looksComplete)
        assertTrue(bip39.draft(ABANDON_PHRASE).looksComplete)
        assertTrue(bip39.draft("$ABANDON_PHRASE ").looksComplete)
    }

    // region Swift parity pitfalls (vault-crypto.md §17.10)

    @Test
    fun prefixesCompareWholeCharactersLikeSwiftHasPrefix() {
        val japanese = bip39.wordlist(Bip39Language.Japanese)
        // "か" + U+3099 is one Character (が): typed "か" is not its prefix, but every word starting with か is suggested.
        assertTrue(japanese.suggestions("か", limit = 2048).none { it.startsWith("が") })
        assertTrue(japanese.suggestions("が", limit = 2048).all { it.startsWith("が") })
        assertTrue(japanese.suggestions("が").isNotEmpty())
        val korean = bip39.wordlist(Bip39Language.Korean)
        // "가" (L V) is not a prefix of "각…" (L V T, one cluster), but it is of "가격" (가 + 격).
        val ga = korean.suggestions("가", limit = 2048)
        assertTrue("가격" in ga)
        assertTrue(ga.none { it.startsWith("각") })
    }

    @Test
    fun lookupsIgnoreTheDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // Turkish would lowercase "I" to dotless "ı" and break "IDLE", "WINTER"…
            assertEquals(Bip39Language.English, bip39.canonicalize(ABANDON_PHRASE.uppercase(Locale.ROOT)).language)
            assertTrue(bip39.wordlist(Bip39Language.English).contains("IDLE"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun tokensDropNumberingButKeepChineseNumeralsAndSplitHanRuns() {
        assertEquals(listOf("abandon", "about"), bip39.tokens("1. abandon, 2) about"))
        assertEquals(listOf("abandon", "about"), bip39.tokens("１． abandon，１２） about"))
        assertEquals(listOf("abandon", "about"), bip39.tokens("abandon、about"))
        assertEquals(listOf("一", "十", "人"), bip39.tokens("一十人"))
        assertEquals(listOf("一", "十", "𠀀"), bip39.tokens("一十𠀀"), "split by code point, not UTF-16 unit")
        assertEquals(listOf("a一"), bip39.tokens("a一"))
        // U+0085 and U+3000 separate (Unicode White_Space); U+001C does not (Kotlin's isWhitespace would split).
        assertEquals(listOf("a", "b", "c"), bip39.tokens("a\u0085b　c"))
        assertEquals(listOf("a\u001Cb"), bip39.tokens("a\u001Cb"))
        // A Character that starts with a space is one separator, combining mark included (Swift splits Characters).
        assertEquals(listOf("abandon", "about"), bip39.tokens("abandon ́about"))
        assertTrue(Bip39Text.isNumbering("¹²"))
    }

    @Test
    fun typedAccentsAreDeliberate() {
        assertTrue(Bip39Text.hasTypedAccents(listOf("ábaco")))
        assertTrue(Bip39Text.hasTypedAccents(listOf("ábaco")))
        assertFalse(Bip39Text.hasTypedAccents(listOf("abaco")))
        assertFalse(Bip39Text.hasTypedAccents(listOf("が")), "kana marks are not Latin accents")
        // One typed accent but no exact reading in any list: the accent-insensitive readings are all offered, in
        // detection order (typed accents only rule them out when an exact reading exists, `:267`).
        val italianWithAccent = "nómina solido comodo nucleo pagina armonia nucleo liquido pagina logica nucleo nucleo"
        assertEquals(listOf(Bip39Language.Spanish, Bip39Language.Italian), bip39.interpretations(italianWithAccent).map { it.language })
    }

    @Test
    fun unknownWordsNameTheBestMatchingLanguagesMisses() {
        val error = assertFailsWith<com.bittool.signer.core.crypto.bip39.Bip39Error.UnknownWords> {
            bip39.canonicalize("ábaco abdomen xyzzy ábaco ábaco ábaco ábaco ábaco ábaco ábaco plugh frob")
        }
        assertEquals(listOf("xyzzy", "plugh", "frob"), error.words)
        assertEquals(
            com.bittool.signer.core.model.UiText.Ios("Invalid recovery phrase: %@ are not in the BIP39 word list.", listOf("“xyzzy”, “plugh”, “frob”")),
            error.text,
        )
    }

    @Test
    fun validatedCanonicalIsExactOnly() {
        assertEquals(Bip39Language.English, bip39.validatedCanonical(ABANDON_PHRASE).language)
        assertFailsWith<com.bittool.signer.core.crypto.bip39.Bip39Error.Checksum> {
            bip39.validatedCanonical("nomina solido comodo nucleo pagina armonia nucleo liquido pagina logica nucleo nucleo".replace("nomina", "nómina"))
        }
        assertFailsWith<com.bittool.signer.core.crypto.bip39.Bip39Error.Empty> { bip39.validatedCanonical(" ") }
        assertFailsWith<com.bittool.signer.core.crypto.bip39.Bip39Error.UnsupportedLength> { bip39.validatedCanonical("abandon") }
    }

    // endregion

    // region additive helpers: recovery QR and the suggestion bar

    @Test
    fun recoveryQrTextBecomesEditorText() {
        assertEquals(ABANDON_PHRASE, bip39.recoveryPhraseFromQr("0000".repeat(11) + "0003"))
        assertEquals(ABANDON_PHRASE, bip39.recoveryPhraseFromQr("\n $ABANDON_PHRASE \n"))
        assertNull(bip39.recoveryPhraseFromQr("hello world"))
        assertNull(bip39.recoveryPhraseFromQr(ABANDON_PHRASE.replace("about", "abandon")), "a checksum failure is not a phrase")
        assertNull(bip39.recoveryPhraseFromQr(""))
    }

    @Test
    fun candidatesFollowTheIosAssistant() {
        assertEquals(Bip39Candidates.TypeAWord, bip39.candidates(bip39.draft("")))
        assertEquals(Bip39Candidates.TypeAWord, bip39.candidates(bip39.draft("abandon ")))
        assertEquals(Bip39Candidates.Words(listOf("abandon", "ability", "able", "about", "above")), bip39.candidates(bip39.draft("ab")))
        assertEquals(Bip39Candidates.NoMatch, bip39.candidates(bip39.draft("abandon xq")))
        // A complete word whose only suggestion is itself shows nothing; one with longer words still shows chips.
        assertEquals(Bip39Candidates.Hidden, bip39.candidates(bip39.draft("abandon")))
        assertEquals(Bip39Candidates.Words(listOf("act", "action", "actor", "actress", "actual")), bip39.candidates(bip39.draft("act")))
        assertEquals(Bip39Candidates.ChineseHint, bip39.candidates(bip39.draft("的一")))
        // Spanish detected: canonical accented spellings; an unaccented complete word with one suggestion shows nothing.
        assertEquals(
            Bip39Candidates.Words(listOf("ábaco", "abdomen", "abeja", "abierto", "abogado")),
            bip39.candidates(bip39.draft("abdomen ab")),
        )
        assertEquals(Bip39Candidates.Hidden, bip39.candidates(bip39.draft("abdomen abaco")))
    }

    @Test
    fun insertingACandidateReplacesTheTrailingWord() {
        assertEquals("abandon ability ", Bip39.insertingCandidate("abandon abi", "ability"))
        assertEquals("abdomen, ábaco ", Bip39.insertingCandidate("abdomen, abaco", "ábaco"))
        assertEquals("的　一 ", Bip39.insertingCandidate("的　一", "一"))
        assertEquals("abandon ", Bip39.insertingCandidate("abandon ", "ability"), "no trailing word: unchanged")
        assertEquals("ability ", Bip39.insertingCandidate("abi", "ability"))
    }

    // endregion
}
