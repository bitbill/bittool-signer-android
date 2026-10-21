package com.bittool.signer.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.bittool.signer.core.data.NoticeSection
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.AppLanguage
import com.bittool.signer.feature.settings.about.NoticesUiState
import com.bittool.signer.feature.settings.about.ThirdPartyNoticesContent
import com.bittool.signer.feature.settings.language.LanguageContent
import com.bittool.signer.feature.settings.language.LanguageUiState
import com.bittool.signer.feature.settings.language.languageTag
import com.bittool.signer.feature.settings.reference.NetworkReferenceContent
import com.bittool.signer.feature.settings.reference.NetworkReferenceUiState
import com.bittool.signer.feature.settings.reference.ReferenceTitle
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
        compose.onNodeWithText("British English", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(languageTag(AppLanguage.Arabic)).assertExists()
        compose.onNodeWithText("Arabic", useUnmergedTree = true).assertExists()
    }

    /** The title and the detail line of a language row, as TalkBack reads them. */
    private fun rowTexts(language: AppLanguage): List<String> =
        compose.onNodeWithTag(languageTag(language)).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }

    /**
     * QA sign-12: details come from ICU's dialect names ("Simplified Chinese"), not `Locale.getDisplayName`'s
     * "language (script)" form ("Chinese (Simplified Han)"), and a row whose detail would repeat its title has none.
     */
    @Test
    fun languageRowsAreNamedLikeAListOfLanguages() {
        show { LanguageContent(LanguageUiState(AppLanguage.English, Locale.US), onSelect = {}, onBack = {}) }
        assertEquals(listOf("简体中文", "Simplified Chinese"), rowTexts(AppLanguage.SimplifiedChinese))
        assertEquals(listOf("繁體中文", "Traditional Chinese"), rowTexts(AppLanguage.TraditionalChinese))
        assertEquals(listOf("Español (Latinoamérica)", "Latin American Spanish"), rowTexts(AppLanguage.LatinAmericanSpanish))
        assertEquals(listOf("Português (Brasil)", "Brazilian Portuguese"), rowTexts(AppLanguage.BrazilianPortuguese))
        assertEquals(listOf("English"), rowTexts(AppLanguage.English))
    }

    @Test
    fun inChineseTheChineseRowDoesNotRepeatItsOwnName() {
        RuntimeEnvironment.setQualifiers("+b+zh+Hans")
        show { LanguageContent(LanguageUiState(AppLanguage.SimplifiedChinese, Locale.forLanguageTag("zh-Hans-CN")), onSelect = {}, onBack = {}) }
        assertEquals(listOf("简体中文"), rowTexts(AppLanguage.SimplifiedChinese))
        assertEquals(listOf("繁體中文", "繁体中文"), rowTexts(AppLanguage.TraditionalChinese))
    }

    @Test
    fun inJapaneseChineseIsNamedWithoutNestedScriptNames() {
        RuntimeEnvironment.setQualifiers("+ja")
        show { LanguageContent(LanguageUiState(AppLanguage.Japanese, Locale.JAPAN), onSelect = {}, onBack = {}) }
        val detail = rowTexts(AppLanguage.SimplifiedChinese).last()
        assertTrue('(' !in detail && '（' !in detail && "漢字" !in detail, detail)
        assertEquals(listOf("日本語"), rowTexts(AppLanguage.Japanese))
    }

    /**
     * QA sign-16: the 70–80 column notices re-wrapped into ragged half lines and the `=====` separator broke over two
     * lines. A wrapped sentence now flows as one paragraph and separators are rules, not text.
     */
    @Test
    @Config(qualifiers = "w393dp-h807dp-xxhdpi")
    fun noticesFlowToTheScreenAndSeparatorsAreRules() {
        val licence = "Permission is hereby granted, free of charge, to any person obtaining a copy\n" +
            "of this software and associated documentation files (the \"Software\"), to deal\n" +
            "in the Software without restriction, including without limitation the rights"
        val header = "========================================================================\n" +
            "@adraffy/ens-normalize 1.11.1 — MIT\nhttps://github.com/adraffy/ens-normalize.js"
        show {
            ThirdPartyNoticesContent(
                NoticesUiState(listOf(NoticeSection("BitTool Signer JavaScript engine — third-party notices", listOf(header, licence)))),
                onBack = {},
            )
        }
        compose.onNodeWithText(licence.replace('\n', ' '), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("@adraffy/ens-normalize 1.11.1 — MIT", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("https://github.com/adraffy/ens-normalize.js", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("=====", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }
}
