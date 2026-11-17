package com.bittool.signer.core.l10n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.review.ReviewPresentation
import com.bittool.signer.core.model.review.ReviewText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * iOS Revision 15 against the real catalogue: warnings and refusals the bundled engine really produces
 * (`src/test/resources/engine-live-texts.json`, collected by running the engine on public fixtures — a Solana v0
 * transfer through an address lookup table, an unnamed EVM chain, a high fee, four EIP-7702 delegations and four
 * refusals) are drawn in the interface language with every value kept, and no placeholder ever reaches the screen.
 */
@RunWith(RobolectricTestRunner::class)
class EngineSentenceTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    private data class Live(val source: String, val kind: String, val text: String)

    private val live: List<Live> by lazy {
        val raw = checkNotNull(javaClass.getResource("/engine-live-texts.json")).readText()
        Json.parseToJsonElement(raw).jsonArray.map { e ->
            val o = e.jsonObject
            Live(o["source"]!!.jsonPrimitive.content, o["kind"]!!.jsonPrimitive.content, o["text"]!!.jsonPrimitive.content)
        }
    }

    /** Engine-filled values: numbers, hex addresses, base58 accounts, ticker amounts. */
    private val value = Regex("0x[0-9a-fA-F]{40}|[1-9A-HJ-NP-Za-km-z]{32,44}|\\b[0-9]+(?:\\.[0-9]+)?\\b")

    private val leakedSpecifier = Regex("%(?:[0-9]+\\$)?[sd@]")

    private fun assertTranslatedKeepingValues(language: String) {
        val text: ReviewText = resources.reviewText()
        assertTrue(live.size >= 17)
        for (item in live) {
            val shown = text.sentence(item.text)
            assertNotEquals(item.text, shown, "$language: left in English (${item.source}): ${item.text.take(70)}")
            assertFalse(leakedSpecifier.containsMatchIn(shown), "$language: a placeholder reached the screen: $shown")
            for (v in value.findAll(item.text).map { it.value }.filter { it.length > 1 || it != "0" }) {
                assertTrue(shown.contains(v), "$language: the value $v of ${item.source} was lost: $shown")
            }
            // The same text through the error path every failure display uses.
            assertEquals(shown, UiText.Engine(item.text).resolve(resources))
        }
    }

    @Test
    @Config(qualifiers = "ja")
    fun japaneseTranslatesEveryLiveWarningAndRefusal() = assertTranslatedKeepingValues("ja")

    @Test
    @Config(qualifiers = "b+zh+Hans")
    fun simplifiedChineseTranslatesEveryLiveWarningAndRefusal() = assertTranslatedKeepingValues("zh-Hans")

    @Test
    @Config(qualifiers = "ja")
    fun aReorderedTranslationPutsEachValueInItsPlace() {
        // Japanese says "this instruction's TOTAL accounts, COUNT of which": the catalogue numbers the placeholders.
        val shown = resources.reviewText().sentence("1 of this instruction's 2 accounts come from an address lookup table and cannot be shown.")
        assertEquals("この命令の 2 個のアカウントのうち 1 個はアドレスルックアップテーブル由来で、表示できません。", shown)
    }

    @Test
    @Config(qualifiers = "b+zh+Hans")
    fun theRedWarningStaysRedBecauseSeverityReadsTheEnglish() {
        val warning = live.first { it.source == "solana lookup table" && it.text.startsWith("HIGH RISK:") }.text
        val shown = resources.reviewText().sentence(warning)
        assertTrue(ReviewPresentation.isSevereWarning(warning))
        assertFalse(shown.startsWith("HIGH RISK"), "translated: $shown")
    }

    @Test
    fun englishStaysEnglishAndAnUnknownSentenceIsUntouched() {
        val text = resources.reviewText()
        val refusal = live.first { it.source == "unsupported chain id 0" }.text
        assertEquals(refusal, text.sentence(refusal))
        assertEquals(refusal, UiText.Engine(refusal).resolve(resources))
        val invented = "HIGH RISK: A warning no translation table has ever seen."
        assertEquals(invented, UiText.Engine(invented).resolve(resources))
        // A value-carrying warning comes back with its values in English too.
        val fee = live.first { it.source == "evm high fee" }.text
        assertTrue(text.sentence(fee).contains("1 ETH"))
    }

    @Test
    @Config(qualifiers = "de")
    fun bothFormsOfTheAssembledLeafOwnerWarningAreTranslated() {
        // iOS §3: built from a branch, so each whole form is a key of its own rather than a template.
        val text = resources.reviewText()
        for (form in listOf(
            "The leaf owner does not sign this transaction, so it will fail on-chain.",
            "The leaf owner or payer does not sign this transaction, so it will fail on-chain.",
        )) {
            assertNotEquals(form, text.sentence(form))
        }
    }
}
