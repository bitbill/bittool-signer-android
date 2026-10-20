package com.bittool.signer.core.ui.text

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.UiText
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Backlog B15: engine error text is shown verbatim (iOS `Callout(verbatim:)`), so in a right-to-left interface an
 * English engine message keeps its own direction — whether it reaches the screen as engine English, as a Kotlin-host
 * engine message (an Android string, English until the translation pass) or as a catalogue key.
 */
@RunWith(RobolectricTestRunner::class)
class MessageCalloutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun errorMessagesKeepTheirOwnDirectionRightToLeft() {
        val engine = "Unsupported PSBT version (v3)."
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column {
                        Callout(UiText.Verbatim(engine), tone = Tone.Danger)
                        Callout(UiText.Resource(R.string.data_engine_failure), tone = Tone.Danger)
                        Callout(UiText.Ios("The password is incorrect."), tone = Tone.Warning)
                        Callout(UiText.Ios("An engine sentence the catalogue does not have."))
                        Callout(UiText.Verbatim("Shown in the interface direction."), verbatim = false)
                    }
                }
            }
        }
        fun layout(text: String): TextLayoutResult {
            val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            return layouts.single()
        }
        val messages = listOf(
            engine,
            "The offline engine could not process this request.",
            "The password is incorrect.",
            "An engine sentence the catalogue does not have.",
        )
        for (message in messages) {
            val shown = layout(message)
            assertEquals(TextDirection.Content, shown.layoutInput.style.textDirection, message)
            assertEquals(ResolvedTextDirection.Ltr, shown.getParagraphDirection(0), message)
        }
        assertEquals(TextDirection.Unspecified, layout("Shown in the interface direction.").layoutInput.style.textDirection)
    }
}
