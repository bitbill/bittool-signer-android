package com.bittool.signer.core.model.review

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Port of iOS `BitToolSignerTests/ReviewWarningTests.swift` (Revision 15): translating the warnings and refusals the
 * offline engine writes. The engine emits English — that text is what the request fingerprint covers — so the
 * interface translates at the last moment and has to put the engine's own values back into a sentence written in
 * another language. The tests that need the real catalogue are `EngineSentenceTest` in `:core:l10n`.
 */
class ReviewWarningTest {
    private val english = ReviewText(EngineTextLookup.None)

    @Test
    fun aWarningWithNoTranslationKeepsTheEnginesOwnWords() {
        // A warning added to the engine and not yet translated must still be readable, not blank.
        val invented = "HIGH RISK: A warning no translation table has ever seen."
        assertEquals(invented, english.sentence(invented))
        assertEquals("", english.sentence(""))
    }

    @Test
    fun aTemplateFindsTheValuesTheEngineFilledIn() {
        val template = ReviewText.Template("HIGH FEE: This transaction allows up to %@ in network fees. Check the gas settings in your other wallet.")
        assertEquals(
            listOf("0.08 ETH"),
            template.match("HIGH FEE: This transaction allows up to 0.08 ETH in network fees. Check the gas settings in your other wallet."),
        )
        // A different warning is not this one, however similar.
        assertNull(template.match("HIGH FEE: This transaction allows up to 1 SOL in priority fees. Check the fee settings in your other wallet."))
        assertNull(template.match(""))
    }

    @Test
    fun severalValuesComeBackInOrder() {
        val template = ReviewText.Template(
            "HIGH RISK: %@ hands the account %@ to the contract at %@. That contract’s code then runs as that account and can move everything in it.",
        )
        val values = template.match(
            "HIGH RISK: Authorization 2 hands the account 0xAAA to the contract at 0xBBB. That contract’s code then runs as that account and can move everything in it.",
        )
        assertEquals(listOf("Authorization 2", "0xAAA", "0xBBB"), values)
    }

    @Test
    fun aValueThatLooksLikeAPatternIsStillJustAValue() {
        // Regular-expression characters in an address or an amount must not change what the template matches.
        val template = ReviewText.Template("This call also sends %@ to the contract.")
        assertEquals(listOf("1.5 (ETH) [*] \\Q\\E $1"), template.match("This call also sends 1.5 (ETH) [*] \\Q\\E $1 to the contract."))
    }

    @Test
    fun everyRegisteredTemplateMatchesItsOwnSentence() {
        // Each template is both the translation key and the pattern, so a template that cannot match its own text
        // would silently never fire.
        assertEquals(96, ReviewText.TEMPLATES.size, "the iOS table (ReviewTextTemplates.swift), entry for entry")
        for (template in ReviewText.TEMPLATES) {
            val filled = template.english.replace("%@", "VALUE")
            val values = assertNotNull(template.match(filled), "no self-match: ${template.english.take(60)}")
            assertTrue(values.all { it == "VALUE" }, "wrong capture: ${template.english.take(60)}")
        }
    }

    @Test
    fun noTwoTemplatesClaimTheSameSentence() {
        // Two templates that both match one warning would make the translation depend on declaration order.
        for (template in ReviewText.TEMPLATES) {
            val filled = template.english.replace("%@", "VALUE")
            val matching = ReviewText.TEMPLATES.filter { it.match(filled) != null }
            assertEquals(1, matching.size, "${matching.size} templates match: ${template.english.take(60)}")
        }
    }

    @Test
    fun aTranslationThatReordersTheValuesStillGetsThemRight() {
        // Several languages say "this instruction's TOTAL accounts, COUNT of which", reversing the engine's order, so
        // their translations number the placeholders (the importer writes Android's %1$s / %2$s). The numbering has
        // to survive substitution.
        assertEquals(
            "this instruction’s 7 accounts, 2 of which, come from a lookup table.",
            ReviewText.substitute("this instruction’s %2\$s accounts, %1\$s of which, come from a lookup table.", listOf("2", "7")),
        )
        assertEquals(
            "2 of this instruction’s 7 accounts come from an address lookup table and cannot be shown.",
            ReviewText.substitute("%1\$s of this instruction’s %2\$s accounts come from an address lookup table and cannot be shown.", listOf("2", "7")),
        )
        assertEquals("This message is 1500 bytes.", ReviewText.substitute("This message is %s bytes.", listOf("1500")))
        assertEquals("100% of 3", ReviewText.substitute("100%% of %s", listOf("3")))
        // A translation whose placeholders disagree with the values is refused, never drawn with a value in another
        // value's place.
        assertNull(ReviewText.substitute("only one %s here", listOf("2", "7")))
        assertNull(ReviewText.substitute("%1\$s and %1\$s", listOf("2", "7")))
        assertNull(ReviewText.substitute("%1\$s and %s", listOf("2", "7")))
    }

    @Test
    fun aMisfitTranslationFallsBackToTheEnglishNotToAHalfSubstitutedOne() {
        val key = "%@ removes the code delegation on %@. That restores a plain account and takes no new permission."
        val broken = ReviewText { if (it == key) "%1\$s は委任を解除します。" else null }
        val warning = "Authorization 1 removes the code delegation on 0xAAA. That restores a plain account and takes no new permission."
        assertEquals(warning, broken.sentence(warning))
        val good = ReviewText { if (it == key) "%1\$s は %2\$s の委任を解除します。" else null }
        assertEquals("Authorization 1 は 0xAAA の委任を解除します。", good.sentence(warning))
    }

    @Test
    fun theEnginesStraightApostropheFindsTheCataloguesTypographicOne() {
        // The engine writes "transaction's"; the catalogue keys (and iOS's templates) say "transaction’s". Without
        // this the lookup-table and EIP-7702 warnings — the HIGH RISK ones of this release — would stay in English.
        val key = "%@ of this instruction’s %@ accounts come from an address lookup table and cannot be shown."
        val japanese = ReviewText { if (it == key) "この命令の %2\$s 個のアカウントのうち %1\$s 個はアドレスルックアップテーブル由来で、表示できません。" else null }
        assertEquals(
            "この命令の 2 個のアカウントのうち 1 個はアドレスルックアップテーブル由来で、表示できません。",
            japanese.sentence("1 of this instruction's 2 accounts come from an address lookup table and cannot be shown."),
        )
        val whole = ReviewText { if (it == "This wallet’s identity signature could not be produced.") "此钱包的身份签名无法生成。" else null }
        assertEquals("此钱包的身份签名无法生成。", whole.sentence("This wallet's identity signature could not be produced."))
        // Text that is neither stays the engine's own.
        assertEquals("Nobody's sentence.", whole.sentence("Nobody's sentence."))
    }

    @Test
    fun numberedPartsAreTranslatedAroundTheirNumbers() {
        // iOS ReviewTextCoverageTests (2026-11-17): a table that marks what it translated, in Android's specifiers, so
        // the shape of the result is visible — a numbered prefix, a label split at " · ", a unit kept as the engine wrote
        // it, and a severity marker kept with its sentence.
        val table = mapOf(
            "Order %@" to "«Order %s»", "Call %@" to "«Call %s»", "Instruction %@" to "«Instruction %s»",
            "Amount" to "«Amount»", "Also paid" to "«Also paid»", "Item %@" to "«Item %s»",
            "HIGH RISK: You receive nothing for the items you give." to "«HIGH RISK: nothing»",
            "UNLIMITED spending approval." to "«UNLIMITED.»",
        )
        val marked = ReviewText { table[it] }
        assertEquals(
            ReviewText.Rendered("«Order 2»: «HIGH RISK: nothing»", complete = true),
            marked.render("HIGH RISK: Order 2: You receive nothing for the items you give.", ReviewText.Kind.Sentence),
        )
        assertEquals(
            ReviewText.Rendered("«UNLIMITED.» («Call 3»)", complete = true),
            marked.render("UNLIMITED spending approval (Call 3).", ReviewText.Kind.Sentence),
        )
        assertEquals(
            ReviewText.Rendered("«Instruction 4» · «Amount» (USDC)", complete = true),
            marked.render("Instruction 4 · Amount (USDC)", ReviewText.Kind.Label),
        )
        assertEquals(
            ReviewText.Rendered("«Order 3» · «Also paid» · «Item 2»", complete = true),
            marked.render("Order 3 · Also paid · Item 2", ReviewText.Kind.Label),
        )
        // A numbered sentence that does not translate in full is drawn exactly as the engine wrote it, never reassembled
        // with its marker moved — which is what English (no table) would otherwise get.
        val original = "HIGH RISK: Order 2: Unknown token contract: amounts are shown in raw units; token metadata is unavailable offline."
        assertEquals(ReviewText.Rendered.english(original), english.render(original, ReviewText.Kind.Sentence))
        assertEquals(ReviewText.Rendered.english(original), marked.render(original, ReviewText.Kind.Sentence), "label translates, sentence does not")
        assertEquals(
            ReviewText.Rendered.english("UNLIMITED spending approval (Call 3)."),
            english.render("UNLIMITED spending approval (Call 3).", ReviewText.Kind.Sentence),
        )
        // Anything the table lacks is drawn in English and reported as such.
        assertFalse(marked.render("Order 3 · Zone hash", ReviewText.Kind.Label).complete)
        assertFalse(marked.render("Instruction 4 · Amount (stored, not enforced)", ReviewText.Kind.Label).complete)
    }

    @Test
    fun aMessageIsNeverShownTranslated() {
        // The words of a message are what gets signed; one that happens to equal a catalogue entry is drawn as it is.
        assertTrue(ReviewText.headlineIsTheirs("Sign Message"))
        assertTrue(ReviewText.headlineIsTheirs("Sign Typed Data"))
        assertFalse(ReviewText.headlineIsTheirs("Send"))
        assertFalse(ReviewText.headlineIsTheirs(null))
    }

    @Test
    fun unitWordsTranslateAndSymbolsNever() {
        // iOS ReviewText.unit: numbers are never looked up, words are; a symbol the catalogue lacks stays as written.
        val table = mapOf("raw units" to "単位", "native coin" to "ネイティブ", "Unlimited" to "無制限", "1000" to "WRONG")
        val text = ReviewText { table[it] }
        assertEquals("1000 単位", text.amountLine("1000", "raw units"), "a number is never looked up")
        assertEquals("0.5 ネイティブ", text.amountLine("0.5", "native coin"))
        assertEquals("無制限 USDC", text.amountLine("Unlimited", "USDC"))
        assertEquals("12.5", text.amountLine("12.5", null))
    }

    @Test
    fun aPercentSignThatIsNotAPlaceholderIsText() {
        // "The maker fee is %@% (%@ bps)": the importer writes Android specifiers and leaves the lone % as it is.
        assertEquals(
            "HIGH RISK: The maker fee is 2.5% (250 bps) of each fill, paid to 0xAAA.",
            ReviewText.substitute("HIGH RISK: The maker fee is %1\$s% (%2\$s bps) of each fill, paid to %3\$s.", listOf("2.5", "250", "0xAAA")),
        )
        val key = "HIGH RISK: The maker fee is %@% (%@ bps) of each fill, paid to %@."
        val japanese = ReviewText { if (it == key) "高リスク：メイカー手数料は約定ごとに %1\$s% (%2\$s bps) で、%3\$s に支払われます。" else null }
        assertEquals(
            "高リスク：メイカー手数料は約定ごとに 2.5% (250 bps) で、0xAAA に支払われます。",
            japanese.sentence("HIGH RISK: The maker fee is 2.5% (250 bps) of each fill, paid to 0xAAA."),
        )
    }

    @Test
    fun severityIsDecidedFromTheEnglishAlone() {
        // "HIGH RISK" is a marker in the engine's source text; a translation that words it differently must not turn
        // a red warning yellow, so callers read the English.
        val warning = "HIGH RISK: Call 2 uses DELEGATECALL."
        val translated = ReviewText { if (it == "HIGH RISK: Call %@ uses DELEGATECALL.") "高风险：调用 %s 使用 DELEGATECALL。" else null }.sentence(warning)
        assertEquals("高风险：调用 2 使用 DELEGATECALL。", translated)
        assertTrue(ReviewPresentation.isSevereWarning(warning))
        assertTrue(!ReviewPresentation.isSevereWarning(translated), "the translation alone would not be recognised")
    }
}
