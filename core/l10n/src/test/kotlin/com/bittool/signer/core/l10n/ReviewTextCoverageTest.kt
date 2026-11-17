package com.bittool.signer.core.l10n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.model.review.ReviewText
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Every piece of engine text the review screen can draw reaches a person in their language, measured on what the engine
 * actually produces, not on its source code (port of iOS `ReviewTextCoverageTests`, 2026-11-17, audit M-01: about half
 * the warnings, HIGH RISK ones among them, stayed English in all 20 languages).
 *
 * `engine/test/fixtures/review-text-corpus.json` is recorded on iOS by running the engine's whole test suite (`node
 * test/support/review-text-corpus.mjs` in `engine/`) and keeping every warning, refusal, title, action, headline, label
 * and unit word its reviews contain; `tools/engine/sync-from-ios.sh` brings it over with the engine source. Each text is
 * drawn through the real [ReviewText] and the real Android resources of every shipped language, and any that would fall
 * back to English fails. `refusalSource` (malformed CBOR/UR errors, mostly) is recorded but not required.
 */
@RunWith(RobolectricTestRunner::class)
class ReviewTextCoverageTest {
    private val corpus: Map<String, List<String>> by lazy {
        // Unit tests run with the module directory as the working directory.
        val file = File("../../engine/test/fixtures/review-text-corpus.json")
        check(file.isFile) { "missing ${file.path}: run tools/engine/sync-from-ios.sh" }
        Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, list) -> list.jsonArray.map { it.jsonPrimitive.content } }
    }

    /** How each kind of text is drawn, as the review card, Activity and the result page draw it. */
    private val kinds = listOf(
        "warning" to ReviewText.Kind.Sentence, "refusal" to ReviewText.Kind.Sentence, "note" to ReviewText.Kind.Sentence,
        "title" to ReviewText.Kind.Phrase, "action" to ReviewText.Kind.Phrase, "counterpartyLabel" to ReviewText.Kind.Phrase,
        "amountWord" to ReviewText.Kind.Phrase, "symbol" to ReviewText.Kind.Phrase, "networkPhrase" to ReviewText.Kind.Phrase,
        "label" to ReviewText.Kind.Label, "assetContractLabel" to ReviewText.Kind.Label, "headline" to ReviewText.Kind.Headline,
    )

    /**
     * Headlines that are values with no words in them: NFT IDs, amounts, call and order lists the engine assembles from
     * the request. They are drawn as the engine wrote them, in every language (the iOS list, 61 headlines).
     */
    private val valueHeadlines = listOf(
        "^[0-9]", "^NFT #", "^For (ANY|NFT)", "^Destroys [0-9.]+ (tokens?|NFT|raw units?)\\b", "^Give [0-9]",
        "^Listings root ", "^Merkle root ",
    ).map(::Regex)

    /** The shipped translations: the Robolectric qualifier of each, and the code its table names itself by. */
    private val languages = listOf(
        "ar" to "ar", "de" to "de", "b+es+419" to "es-419", "es" to "es", "fil" to "fil", "fr" to "fr", "b+id" to "id",
        "it" to "it", "ja" to "ja", "ko" to "ko", "pl" to "pl", "pt-rBR" to "pt-BR", "pt-rPT" to "pt-PT", "ru" to "ru",
        "th" to "th", "tr" to "tr", "uk" to "uk", "vi" to "vi", "b+zh+Hans" to "zh-Hans", "b+zh+Hant" to "zh-Hant",
    )

    /** An iOS placeholder, or an Android specifier `substitute` should have filled. */
    private val leakedPlaceholder = Regex("%(?:[0-9]+\\$)?[sd@]|\\$@")

    private fun reviewText(qualifiers: String): Pair<ReviewText, String> {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        return resources.reviewText() to resources.getString(R.string.l10n_table_language)
    }

    @Test
    fun theCorpusIsThere() {
        // Guard the guard: an empty or unreadable corpus would make the test below pass.
        assertTrue((corpus["warning"]?.size ?: 0) > 200, "review-text-corpus.json is missing or too small")
        assertTrue((corpus["label"]?.size ?: 0) > 500)
        assertEquals(20, languages.size)
    }

    @Test
    fun everyEngineTextIsTranslatedInEveryLanguage() {
        val report = mutableListOf<String>()
        for ((qualifiers, code) in languages) {
            val (text, table) = reviewText(qualifiers)
            assertEquals(code, table, "$qualifiers loaded the wrong table")
            val english = mutableListOf<String>()
            for ((name, kind) in kinds) {
                for (item in corpus[name].orEmpty()) {
                    if (name == "headline" && valueHeadlines.any { it.containsMatchIn(item) }) continue
                    val shown = text.render(item, kind)
                    assertTrue(!leakedPlaceholder.containsMatchIn(shown.text) || leakedPlaceholder.containsMatchIn(item), "$code: a placeholder reached the screen: ${shown.text}")
                    if (!shown.complete) english += "[$name] ${item.take(100)}"
                }
            }
            if (english.isNotEmpty()) report += "$code: ${english.size} in English, first: ${english.take(6).joinToString(" | ")}"
        }
        assertTrue(report.isEmpty(), report.joinToString("\n"))
    }

    @Test
    fun englishIsDrawnExactlyAsTheEngineWroteIt() {
        // English has no table (as on iOS): nothing is rebuilt from parts, so "HIGH RISK: Order 2: …" keeps its order.
        val (text, table) = reviewText("en")
        assertEquals("en", table)
        for ((name, kind) in kinds) {
            for (item in corpus[name].orEmpty()) assertEquals(item, text.render(item, kind).text, "[$name] changed in English")
        }
    }
}
