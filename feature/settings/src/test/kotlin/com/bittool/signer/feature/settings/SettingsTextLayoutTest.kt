package com.bittool.signer.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.AppLanguage
import com.bittool.signer.feature.settings.language.LanguageContent
import com.bittool.signer.feature.settings.language.LanguageUiState
import com.bittool.signer.feature.settings.language.languageTag
import com.bittool.signer.feature.settings.reference.NetworkReferenceContent
import com.bittool.signer.feature.settings.reference.NetworkReferenceUiState
import com.bittool.signer.feature.settings.reference.ReferenceTitle
import java.util.Locale
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Text in Settings pages at large sizes and in mixed directions (ARCHITECTURE §4.7). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsTextLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) = compose.setContent {
        val density = LocalDensity.current
        SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    private fun reference(query: String = "", tokens: Boolean = true) = NetworkReferenceUiState(
        loading = false,
        title = ReferenceTitle.Network("Ethereum"),
        tokenCount = if (tokens) 1 else 0,
        contractCount = 0,
        query = query,
        appliedQuery = query,
        tokens = if (tokens) listOf(SettingsTestData.usdt) else emptyList(),
    )

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun theSearchPromptEndsWithAnEllipsisInsteadOfBeingCutAtLargeText() {
        // A small phone at font scale 2.0: the one-line prompt cannot fit and must end in "…", not at the field's edge.
        show(fontScale = 2f) { NetworkReferenceContent(reference(), onBack = {}, onQueryChange = {}, onSegmentSelected = {}) }
        val prompt = layoutOf("Symbol, name or address")
        assertTrue(prompt.isLineEllipsized(0), "the prompt is ellipsized, not clipped")
    }

    @Test
    fun noResultsKeepsTheQueryInItsOwnDirection() {
        // A left-to-right query inside a right-to-left sentence keeps its order: it is wrapped in FSI … PDI.
        show { NetworkReferenceContent(reference(query = "0xdead", tokens = false), onBack = {}, onQueryChange = {}, onSegmentSelected = {}) }
        compose.onNodeWithText("No Results for “⁨0xdead⁩”").assertExists()
    }

    @Test
    fun languageRowsNameEachLanguageWithoutLocaleExtensions() {
        // Arabic is applied as ar-u-nu-latn; its row says "Arabic", not "Arabic (Latin Digits)". "System" names the
        // device language the same way, without its regional-preference extensions.
        val systemLocale = Locale.forLanguageTag("en-GB-u-mu-celsius-fw-mon")
        show { LanguageContent(LanguageUiState(AppLanguage.System, systemLocale), onSelect = {}, onBack = {}) }
        compose.onNodeWithText("English (United Kingdom)", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(languageTag(AppLanguage.Arabic)).assertExists()
        compose.onNodeWithText("Arabic", useUnmergedTree = true).assertExists()
    }
}
