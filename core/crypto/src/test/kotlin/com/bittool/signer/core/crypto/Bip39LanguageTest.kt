package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.bip39.Bip39Canonical
import com.bittool.signer.core.crypto.bip39.Bip39Error
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.crypto.vault.VaultError
import java.io.File
import java.text.Normalizer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Port of iOS `BIP39LanguageTests` (all 9 cases, `BitToolSignerTests/BIP39LanguageTests.swift`) over the shipped
 * assets and the iOS fixture `BIP39LanguageVectors.json` (44 vectors from @scure/bip39 2.4.0 and the official
 * Japanese bip32JP set, 10 invalid-checksum phrases; public, unfunded test phrases).
 */
class Bip39LanguageTest {
    @Serializable
    private data class Vector(
        val language: String,
        val entropy: String,
        val mnemonic: String,
        val passphrase: String,
        val seed: String,
        val source: String? = null,
    )

    @Serializable
    private data class Invalid(val language: String, val mnemonic: String)

    @Serializable
    private data class Fixture(val vectors: List<Vector>, val invalid: List<Invalid>)

    private val bip39 = sharedBip39
    private val seed = Pbkdf2Bip39Seed()

    private val fixture: Fixture by lazy {
        val text = File(javaClass.classLoader!!.getResource("BIP39LanguageVectors.json")!!.toURI()).readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(Fixture.serializer(), text)
    }

    private fun language(raw: String) = Bip39Language.entries.single { it.fileName == raw }

    private fun seedHex(phrase: String, passphrase: String) = String(seed.hex(secret(phrase), secret(passphrase)))

    @Test
    fun allTenOfficialWordlistsLoadWithTheirPinnedHashes() {
        for (language in Bip39Language.entries) {
            assertTrue(bip39.wordlist(language).isAvailable, "${language.fileName} must load and match its pinned SHA-256")
        }
        assertEquals("abandon", bip39.wordlist(Bip39Language.English).words.first())
        assertEquals("あいこくしん", bip39.wordlist(Bip39Language.Japanese).words.first())
        assertEquals("가격", bip39.wordlist(Bip39Language.Korean).words.first())
        assertEquals("ábaco", bip39.wordlist(Bip39Language.Spanish).words.first())
        assertEquals("的", bip39.wordlist(Bip39Language.ChineseSimplified).words.first())
        assertEquals("歇", bip39.wordlist(Bip39Language.ChineseTraditional).words.last())
    }

    @Test
    fun referenceVectorsCanonicalizeAndDeriveTheExpectedSeed() {
        assertTrue(fixture.vectors.size >= 44)
        for (vector in fixture.vectors) {
            val canonical = bip39.canonicalize(vector.mnemonic)
            // Simplified and Traditional Chinese share many characters; the seed depends only on the words.
            if (vector.language.startsWith("chinese")) {
                assertTrue(canonical.language.isChinese)
            } else {
                assertEquals(language(vector.language), canonical.language)
            }
            assertEquals(vector.seed, seedHex(canonical.phrase, vector.passphrase), "${vector.language} ${vector.entropy}")
        }
        assertTrue(fixture.vectors.any { it.source == "bip32JP" })
    }

    @Test
    fun inputIsForgivingButTheSeedUsesTheCanonicalWords() {
        for (vector in fixture.vectors) {
            val canonical = bip39.canonicalize(vector.mnemonic)
            val nfc = Normalizer.normalize(vector.mnemonic, Normalizer.Form.NFC)
            // Japanese backups use ideographic spaces; NFC input from a keyboard.
            assertEquals(canonical, bip39.canonicalize(nfc.replace('　', ' ')))
            // Latin scripts: no accents, capital letters, commas and numbering.
            if (language(vector.language).matchesWithoutAccents) {
                val plain = Normalizer.normalize(vector.mnemonic, Normalizer.Form.NFD)
                    .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                assertEquals(canonical, bip39.canonicalize(plain.uppercase()))
                val numbered = plain.split(' ').mapIndexed { i, word -> "${i + 1}. $word," }.joinToString(" ")
                assertEquals(canonical, bip39.canonicalize(numbered))
            }
            // Chinese typed without spaces.
            if (vector.language.startsWith("chinese")) {
                assertEquals(canonical, bip39.canonicalize(vector.mnemonic.replace(" ", "")))
            }
            // The stored phrase is NFC words joined by single spaces.
            assertEquals(Normalizer.normalize(canonical.phrase, Normalizer.Form.NFC), canonical.phrase)
            assertEquals(vector.mnemonic.split(' ', '　').count { it.isNotEmpty() }, canonical.wordCount)
        }
    }

    @Test
    fun checksumLengthAndUnknownWordsFailClosed() {
        for (invalid in fixture.invalid) {
            assertFailsWith<Bip39Error.Checksum>(invalid.language) { bip39.canonicalize(invalid.mnemonic) }
        }
        assertFailsWith<Bip39Error.Empty> { bip39.canonicalize("  \n ") }
        assertEquals(3, assertFailsWith<Bip39Error.UnsupportedLength> { bip39.canonicalize("abandon abandon abandon") }.count)
        val unknown = List(11) { "abandon" } + "xyzzy"
        assertEquals(listOf("xyzzy"), assertFailsWith<Bip39Error.UnknownWords> { bip39.canonicalize(unknown.joinToString(" ")) }.words)
        // Mixing languages never validates, even when every word exists somewhere.
        val mixed = List(11) { "abandon" } + "ábaco"
        assertFailsWith<Bip39Error> { bip39.canonicalize(mixed.joinToString(" ")) }
    }

    @Test
    fun phrasesValidInTwoLanguagesAskInsteadOfGuessing() {
        // Valid Italian, and valid Spanish once accents are added: two different wallets.
        val italian = "nomina solido comodo nucleo pagina armonia nucleo liquido pagina logica nucleo nucleo"
        val spanish = "nómina sólido cómodo núcleo página armonía núcleo líquido página lógica núcleo núcleo"
        val readings = bip39.interpretations(italian)
        assertEquals(listOf(Bip39Language.Italian, Bip39Language.Spanish), readings.map { it.language })
        assertEquals(readings, assertFailsWith<Bip39Error.Ambiguous> { bip39.canonicalize(italian) }.options)
        assertEquals(
            "ff24afd428aa5040062bdaa2f41b8397b21ac04fef76fff7bc27e6ecf0ba349243005a33f5e1486d9e0ea54fafbba9ae4dc1c54779ab2f3ed1963f872b0e4a78",
            seedHex(readings[0].phrase, ""),
        )
        assertEquals(
            "dc1bb7e8db68b6484ac3fe00d0f356a10f4e33deb6efed27cbff7df2daedded3e8d05053605876bdb87bdd8373d35e4cf6ada4a2a114d08d69b3597a5e2b01d0",
            seedHex(readings[1].phrase, ""),
        )
        // Typed accents are deliberate; stored phrases are never reinterpreted.
        assertEquals(Bip39Language.Spanish, bip39.canonicalize(spanish).language)
        assertEquals(Bip39Language.Italian, bip39.validatedCanonical(readings[0].phrase).language)
        assertEquals(Bip39Language.Spanish, bip39.validatedCanonical(readings[1].phrase).language)
    }

    /**
     * The BIP39 half of iOS `scannedPhrasesKeepTheirExactLanguageWithoutAsking` (the `WalletSetupModel` half is
     * `:feature:setup`'s): a scanned phrase QR fills the editor with its exact words, an unedited scan validates in
     * its own language without asking, while the same words edited or typed by hand are ambiguous.
     */
    @Test
    fun scannedPhrasesKeepTheirExactLanguageWithoutAsking() {
        val italian = bip39.interpretations("nomina solido comodo nucleo pagina armonia nucleo liquido pagina logica nucleo nucleo").first()
        assertEquals(Bip39Language.Italian, italian.language)

        // A QR code (such as BitTool Signer's recovery phrase QR) carries the exact words.
        val scanned = bip39.recoveryPhraseFromQr(italian.phrase)
        assertEquals(italian.phrase, scanned)
        assertEquals(Bip39Canonical(italian.phrase, Bip39Language.Italian), bip39.validatedCanonical(scanned!!))

        // Editing the scanned words (or typing them) drops that shortcut: the person chooses.
        assertEquals(listOf(Bip39Language.Italian, Bip39Language.Spanish), bip39.interpretations("$scanned ").map { it.language })
        assertEquals(listOf(Bip39Language.Italian, Bip39Language.Spanish), bip39.interpretations(italian.phrase).map { it.language })
    }

    /**
     * BTS-04 (audit BTS-SEC-2026-1017): seed derivation must accept the longest phrase any wordlist can produce.
     */
    @Test
    fun everyValidPhraseLengthDerivesASeedInEveryWordlist() {
        for (language in Bip39Language.entries) {
            val words = bip39.wordlist(language).words
            assertEquals(2048, words.size, language.fileName)
            val longest = assertNotNull(words.maxByOrNull { it.encodeToByteArray().size })
            for (count in listOf(12, 15, 18, 21, 24)) {
                val phrase = List(count) { longest }.joinToString(" ")
                assertEquals(128, seed.hex(secret(phrase), secret("")).size, "${language.fileName} $count words")
            }
            val worst = Normalizer.normalize(List(24) { longest }.joinToString(" "), Normalizer.Form.NFKD).encodeToByteArray().size
            assertTrue(worst <= Pbkdf2Bip39Seed.MAXIMUM_PHRASE_BYTES, "${language.fileName} worst case is $worst bytes")
        }
    }

    @Test
    fun thePhraseLimitStillRejectsUnboundedInput() {
        assertFailsWith<VaultError.InvalidSecret> { seed.hex(secret("a".repeat(Pbkdf2Bip39Seed.MAXIMUM_PHRASE_BYTES + 1)), secret("")) }
        assertFailsWith<VaultError.InvalidSecret> { seed.hex(secret(""), secret("")) }
        assertFailsWith<VaultError.InvalidSecret> { seed.hex(secret("abandon"), secret("p".repeat(1_025))) }
    }

    @Test
    fun draftDetectsTheLanguageAndSuggestsCanonicalWords() {
        val spanish = bip39.draft("abaco abdomen abe")
        assertEquals(Bip39Language.Spanish, spanish.language)
        assertTrue(spanish.unknownWords.isEmpty())
        assertEquals("abe", spanish.partialWord)
        assertEquals(listOf("ábaco"), bip39.wordlist(Bip39Language.Spanish).suggestions("abaco"))

        val japanese = bip39.draft("あいこくしん　あいこ")
        assertEquals(Bip39Language.Japanese, japanese.language)
        assertEquals(listOf("あいこくしん", "あいこ"), japanese.words)
        assertTrue(japanese.trailingWordIncomplete)

        val chinese = bip39.draft("的一是在")
        assertEquals(true, chinese.language?.isChinese)
        assertEquals(4, chinese.words.size)

        val english = bip39.draft("abandon abandon ab")
        assertEquals(Bip39Language.English, english.language)
        assertTrue(english.unknownWords.isEmpty())

        assertEquals(listOf("xyzzy"), bip39.draft("abandon xyzzy ").unknownWords)
    }
}
