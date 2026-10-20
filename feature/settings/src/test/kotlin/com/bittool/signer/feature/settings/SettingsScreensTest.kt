package com.bittool.signer.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.AnnotatedString
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.Appearance
import com.bittool.signer.core.model.CompatibleWallets
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.LegalDocumentParser
import com.bittool.signer.core.navigation.NetworksRoute
import com.bittool.signer.core.navigation.SignerRoute
import com.bittool.signer.feature.settings.about.LegalDocumentContent
import com.bittool.signer.feature.settings.about.LegalDocumentUiState
import com.bittool.signer.feature.settings.compatibility.CompatibilityContent
import com.bittool.signer.feature.settings.compatibility.CompatibilityTab
import com.bittool.signer.feature.settings.compatibility.CompatibilityTags
import com.bittool.signer.feature.settings.compatibility.CompatibilityViewModel
import com.bittool.signer.feature.settings.compatibility.DirectoryTags
import com.bittool.signer.feature.settings.reference.NetworkReferenceContent
import com.bittool.signer.feature.settings.reference.NetworkReferenceUiState
import com.bittool.signer.feature.settings.reference.ReferenceTitle
import com.bittool.signer.core.data.testing.FakeLegalDocumentRepository
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings pages rendered with Robolectric (English, light). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp-xhdpi")
class SettingsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        SignerTheme(darkTheme = false, applySystemBarAppearance = false) { content() }
    }

    @Test
    fun aLegalDocumentRendersWithoutLinks() {
        // LegalDocumentTests.documentIsBundledAndRendersWithoutLinks (rendering half): the one address the documents
        // name is plain text — no LinkAnnotation, no URL span, nothing clickable — and the title is the English one.
        val text = """
            Terms of Use
            Version 1.1 · Effective 16 October 2026

            ## 1. This agreement
            These **terms** govern BitTool Signer.

            ## 16. Contact
            BITTOOL PTE. LTD.

            support@bittool.com

            https://bittool.com/signer
        """.trimIndent()
        val blocks = FakeLegalDocumentRepository.parsed(LegalDocument.Terms, text)
        show { LegalDocumentContent(LegalDocumentUiState(LegalDocument.Terms, blocks), onBack = {}) }
        compose.onNodeWithText("Terms of Use").assertIsDisplayed()
        compose.onNodeWithText("Version 1.1 · Effective 16 October 2026").assertIsDisplayed()
        compose.onNodeWithTag(LegalDocument.Terms.testTag).performScrollToNode(hasText("https://bittool.com/signer"))
        val site = compose.onNodeWithText("https://bittool.com/signer").fetchSemanticsNode()
        assertEquals(null, site.config.getOrNull(SemanticsActions.OnClick), "the site must not be clickable")
        assertNoLinks(compose)
        // `**bold**` is drawn bold, never with its asterisks.
        compose.onAllNodesWithText("**", substring = true).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun aMissingDocumentSaysSoInTheInterfaceLanguage() {
        val missing = listOf(LegalBlock(0, LegalBlock.Kind.Paragraph, LegalDocumentParser.MISSING))
        show { LegalDocumentContent(LegalDocumentUiState(LegalDocument.Privacy, missing), onBack = {}) }
        compose.onNodeWithText("Privacy Policy").assertIsDisplayed()
        compose.onNodeWithText("This document is missing from the app bundle.").assertIsDisplayed()
    }

    @Test
    fun theSettingsRootListsEverySectionInTheIosOrder() {
        val opened = mutableListOf<SignerRoute>()
        var themeClicks = 0
        show {
            SettingsContent(
                state = SettingsUiState(appLockToggleEnabled = false, biometricsAvailable = false, version = "1.0.0 (1)", appearance = Appearance.Dark),
                onAppLockChange = {},
                onThemeClick = { themeClicks++ },
                onThemeSelected = {},
                onThemeDismissed = {},
                onNavigate = { opened += it },
            )
        }
        compose.onNodeWithTag(SettingsTags.APP_LOCK).assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("App Lock", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(
            "Set up fingerprint or face unlock in Android Settings to lock BitTool Signer. Every signature always needs the wallet’s password.",
        ).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.APPEARANCE).assert(hasText("Dark")).performClick()
        assertEquals(1, themeClicks)
        compose.onNodeWithTag(SettingsTags.NETWORKS).performClick()
        assertEquals(listOf<SignerRoute>(NetworksRoute), opened)
        val list = compose.onNodeWithTag(SettingsTags.LIST)
        list.performScrollToNode(hasTestTag(SettingsTags.PRIVACY))
        compose.onNodeWithText("1.0.0 (1)", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Network connections", useUnmergedTree = true).assertExists()
        list.performScrollToNode(hasText("Air-gapped signing. For everyone."))
        // No "Offline Setup Guide" row (gaps.md G1.18; the guide is onboarding only).
        compose.onNodeWithText("Offline Setup Guide", substring = true).assertDoesNotExist()
    }

    @Test
    fun theThemeDialogOffersSystemLightAndDark() {
        var chosen: Appearance? = null
        show {
            SettingsContent(
                state = SettingsUiState(appearance = Appearance.System, showsThemeDialog = true, version = "1.0.0 (1)"),
                onAppLockChange = {},
                onThemeClick = {},
                onThemeSelected = { chosen = it },
                onThemeDismissed = {},
                onNavigate = {},
            )
        }
        compose.onNodeWithTag(SettingsTags.theme(Appearance.Light)).performClick()
        assertEquals(Appearance.Light, chosen)
    }

    @Test
    fun theWalletDirectoryIsTextToCopyWithoutLinks() {
        val viewModel = CompatibilityViewModel(androidx.lifecycle.SavedStateHandle())
        show {
            CompatibilityContent(
                tab = CompatibilityTab.Wallets,
                chains = viewModel.chains,
                walletLevel = viewModel.walletLevel,
                directory = viewModel.directory,
                onTabSelected = {},
                onBack = {},
                onSubmitWallet = {},
            )
        }
        compose.onNodeWithText("says it supports", substring = true).assertExists()
        compose.onNodeWithText("Anyone can submit a wallet", substring = true).assertExists()
        compose.onNodeWithText("BitTool is not responsible", substring = true).assertExists()
        compose.onNodeWithTag(DirectoryTags.LIST).performScrollToNode(hasTestTag(DirectoryTags.wallet("Sparrow")))
        compose.onNodeWithText("sparrowwallet.com").assertExists()
        compose.onNodeWithText("${CompatibleWallets.directory.size} wallets").assertExists()
        assertNoLinks(compose)
    }

    @Test
    fun theFormatsSegmentNamesEachChainsWallets() {
        val viewModel = CompatibilityViewModel(androidx.lifecycle.SavedStateHandle())
        var selected: CompatibilityTab? = null
        show {
            CompatibilityContent(
                tab = CompatibilityTab.Formats,
                chains = viewModel.chains,
                walletLevel = viewModel.walletLevel,
                directory = viewModel.directory,
                onTabSelected = { selected = it },
                onBack = {},
                onSubmitWallet = {},
            )
        }
        compose.onNodeWithText("crypto-account (account xpub), crypto-psbt, BBQR, Base64 PSBT").assertExists()
        compose.onNodeWithTag(CompatibilityTags.FORMATS_LIST).performScrollToNode(hasText("Ethereum & EVM"))
        compose.onNodeWithTag(CompatibilityTags.FORMATS_LIST).performScrollToNode(hasText("Wallet Level · all chains"))
        compose.onNodeWithText("bittool-signer-wallet v1 (ur:bytes, canonical CBOR)", substring = true).assertExists()
        compose.onNodeWithTag(CompatibilityTags.WALLETS).performClick()
        assertEquals(CompatibilityTab.Wallets, selected)
    }

    @Test
    fun anEmptySearchSaysNoResultsForTheQuery() {
        show {
            NetworkReferenceContent(
                state = NetworkReferenceUiState(
                    loading = false,
                    title = ReferenceTitle.Network("Ethereum"),
                    tokenCount = 3,
                    contractCount = 2,
                    query = "zzz",
                    appliedQuery = "zzz",
                ),
                onBack = {},
                onQueryChange = {},
                onSegmentSelected = {},
            )
        }
        compose.onNodeWithText("No Results for “zzz”").assertIsDisplayed()
        compose.onNodeWithText("Check the spelling or try a new search.").assertIsDisplayed()
        compose.onNodeWithText("3 Tokens").assertExists()
        compose.onNodeWithText("2 Contracts").assertExists()
    }

    /** No text on screen carries a link (LinkAnnotation / URL annotation): the app opens no browser. */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    private fun assertNoLinks(provider: SemanticsNodeInteractionsProvider) {
        val nodes = provider.onAllNodesWithText("", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
        for (node in nodes) {
            val texts: List<AnnotatedString> = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            for (text in texts) {
                assertTrue(text.getLinkAnnotations(0, text.length).isEmpty(), "a link in \"$text\"")
                @Suppress("DEPRECATION")
                assertTrue(text.getUrlAnnotations(0, text.length).isEmpty(), "a URL span in \"$text\"")
            }
        }
    }
}
