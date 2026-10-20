package com.bittool.signer.feature.onboarding

import android.content.res.Resources
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.LegalDocumentParser
import com.bittool.signer.core.navigation.SetupIntent
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Welcome (iOS `WelcomeView`; app-shell.md §5): the copy, the two ways in, the legal links and their test tags. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h900dp")
class WelcomeContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val started = mutableListOf<SetupIntent>()
    private val legal = mutableListOf<LegalDocument>()

    private fun show() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                WelcomeContent(onStart = { started += it }, onShowLegal = { legal += it })
            }
        }
    }

    @Test
    fun showsTheBrandPromisesAndNetworks() {
        show()
        compose.onNodeWithText("BitTool Signer").assertIsDisplayed()
        compose.onNodeWithText("Air-gapped signing. For everyone.").assertIsDisplayed()
        compose.onNodeWithText("Keys stay on this device").assertExists()
        compose.onNodeWithText("Fully open source").assertExists()
        compose.onNodeWithText("Know what you sign").assertExists()
        compose.onNodeWithText("Bitcoin, Ethereum, BNB Chain, Arbitrum, Base, TRON and Solana").performScrollTo().assertIsDisplayed()
        // The logo stack is one element with the short names (iOS NetworkLogoStack accessibility label).
        compose.onNodeWithContentDescription("Bitcoin, Ethereum, BNB Chain, Arbitrum, Base, TRON, Solana").assertExists()
    }

    @Test
    fun createAndImportStartSetupWithTheirIntent() {
        show()
        compose.onNodeWithTag(WelcomeTags.CREATE).assertHasClickAction().performClick()
        compose.onNodeWithTag(WelcomeTags.IMPORT).performClick()
        compose.waitForIdle()
        assertEquals(listOf(SetupIntent.Create, SetupIntent.Restore), started)
    }

    @Test
    fun theLegalLinksOpenTheirDocuments() {
        show()
        compose.onNodeWithTag(WelcomeTags.TERMS).performClick()
        compose.onNodeWithTag(WelcomeTags.PRIVACY).performClick()
        compose.waitForIdle()
        assertEquals(listOf(LegalDocument.Terms, LegalDocument.Privacy), legal)
    }

    @Test
    fun anUnreadableDocumentShowsTheMissingParagraphInTheAppLanguage() {
        // The repository hands over the parser's English block; the sheet shows the strings_settings.xml text, as the
        // Settings legal pages do (docs/qa/backlog.md B14). A stand-in translation proves which one is drawn.
        val translated = "Dieses Dokument fehlt im App-Paket."
        val sheet = LegalSheet(
            LegalDocument.Privacy,
            listOf(LegalBlock(0, LegalBlock.Kind.Paragraph, LegalDocumentParser.MISSING)),
        )
        var done = 0
        compose.setContent {
            val resources = LocalResources.current
            val german = remember(resources) { OneStringResources(resources, R.string.settings_document_missing, translated) }
            CompositionLocalProvider(LocalResources provides german) {
                SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                    LegalSheetContent(sheet = sheet, onDone = { done++ })
                }
            }
        }
        compose.onNodeWithText(translated).assertIsDisplayed()
        compose.onNodeWithText(LegalDocumentParser.MISSING).assertDoesNotExist()
        compose.onNodeWithTag(WelcomeTags.LEGAL_DONE).performClick()
        assertEquals(1, done)
    }

    /** [base] with one string replaced: a translation the test's locale does not ship yet. */
    @Suppress("DEPRECATION")
    private class OneStringResources(base: Resources, private val replaced: Int, private val text: String) :
        Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun getText(id: Int): CharSequence = if (id == replaced) text else super.getText(id)

        override fun getString(id: Int): String = if (id == replaced) text else super.getString(id)
    }
}
