package com.bittool.signer.root

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.UiText
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Unable to Continue" (iOS RootView.swift:66-68, `Text(store.error)`): the message is shown as given, in its own
 * direction (backlog B25). In the Arabic interface an English store or engine message stays a left-to-right paragraph
 * (its trailing period and parentheses stay where they belong); Arabic text stays right-to-left. Native graphics, so
 * the paragraph direction comes from the platform's real text layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ar")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnableToContinueDialogTest {
    @get:Rule
    val compose = createComposeRule()

    /** "Unable to Continue" in the interface language of the test (Arabic). */
    private val title: String get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.unable_to_continue)

    private fun show(error: UiText, onDismiss: () -> Unit = {}) {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                UnableToContinueDialog(error = error, onDismiss = onDismiss)
            }
        }
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.paragraphDirection(): ResolvedTextDirection {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().getParagraphDirection(0)
    }

    private fun messageDirection() = compose.onNodeWithTag(RootTags.ERROR_MESSAGE).paragraphDirection()

    @Test
    fun anEnglishMessageKeepsItsDirectionInARightToLeftInterface() {
        val text = "Invalid base64 bytes (input 0)."
        var dismissed = 0
        show(UiText.Verbatim(text)) { dismissed++ }
        // The dialog window is right-to-left: its interface text (the title) follows the layout direction.
        assertEquals(ResolvedTextDirection.Rtl, compose.onNodeWithText(title).assertIsDisplayed().paragraphDirection())
        compose.onNodeWithText(text).assertIsDisplayed()
        assertEquals(ResolvedTextDirection.Ltr, messageDirection())
        compose.onNodeWithTag(RootTags.ERROR_OK).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun anArabicMessageStaysRightToLeft() {
        show(UiText.Verbatim("تعذّر حفظ سجل النشاط المحلي."))
        assertEquals(ResolvedTextDirection.Rtl, messageDirection())
    }
}
