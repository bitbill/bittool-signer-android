package com.bittool.signer.core.ui.password

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Port of iOS `PassphraseFieldTests` (2 field cases) plus the Android guards of design-system §9.5. */
@RunWith(RobolectricTestRunner::class)
class PasswordFieldTest {
    @get:Rule
    val compose = createComposeRule()

    private fun content(body: @Composable () -> Unit) {
        compose.setContent { SignerTheme(darkTheme = false, applySystemBarAppearance = false) { Column { body() } } }
    }

    /** iOS `onlyPassphraseFieldsOfferTheEyeButton`. */
    @Test
    fun onlyPassphraseFieldsOfferTheEyeButton() {
        content {
            PasswordField(PasswordKind.Passphrase, remember { TextFieldState() }, isNew = true, revealable = true)
            PasswordField(PasswordKind.WalletPassword, remember { TextFieldState() })
        }
        compose.onNodeWithTag(PasswordKind.Passphrase.revealTag).assertExists()
        compose.onNodeWithTag(PasswordKind.WalletPassword.revealTag).assertDoesNotExist()
        compose.onNodeWithTag(PasswordKind.WalletPassword.english).assertExists()
    }

    /** iOS `theAccessibilityIdentifierStaysTheEnglishSourceText`, in a translated interface. */
    @Test
    @Config(qualifiers = "de")
    fun theAccessibilityIdentifierStaysTheEnglishSourceText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        content { PasswordKind.entries.forEach { PasswordField(it, remember { TextFieldState() }) } }
        for (kind in PasswordKind.entries) {
            compose.onNodeWithTag(kind.english).assertExists()
            val placeholder = context.getString(kind.title)
            assertTrue(placeholder.isNotBlank(), "${kind.english} resolves a placeholder")
        }
        assertEquals("Wallet password", PasswordKind.WalletPassword.english)
        assertEquals("Confirm passphrase", PasswordKind.ConfirmPassphrase.english)
        assertEquals("BIP39 passphrase.reveal", PasswordKind.Passphrase.revealTag)
        // The German placeholder differs from the English identifier: the tag is not the translated text.
        assertTrue(context.getString(R.string.wallet_password_2) != "Wallet password")
    }

    @Test
    fun theEyeRevealsThePassphraseAndPausingHidesItAgain() {
        val state = TextFieldState("correct horse")
        val lifecycle = ManualLifecycleOwner()
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                    PasswordField(PasswordKind.Passphrase, state, isNew = true, revealable = true)
                }
            }
        }
        val eye = compose.onNodeWithTag(PasswordKind.Passphrase.revealTag)
        eye.assertContentDescriptionEquals("Show secret")
        compose.onNodeWithTag(PasswordKind.Passphrase.english).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        val field = compose.onNodeWithTag(PasswordKind.Passphrase.english)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsSensitiveData, false))
        eye.performClick()
        eye.assertContentDescriptionEquals("Hide secret")
        compose.runOnIdle { assertTrue(view.isAccessibilityDataSensitive, "a visible passphrase is accessibility-sensitive") }
        // The node itself too: only accessibility tools (TalkBack) are given the revealed text.
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsSensitiveData, true))
        compose.runOnIdle { lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        eye.assertContentDescriptionEquals("Show secret")
        compose.runOnIdle { assertFalse(view.isAccessibilityDataSensitive) }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsSensitiveData, false))
        assertEquals("correct horse", state.text.toString(), "hiding keeps what was typed")
    }

    @Test
    fun secretFieldsAreExcludedFromAutofill() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                PasswordField(PasswordKind.NewPassword, remember { TextFieldState() }, isNew = true)
            }
        }
        compose.runOnIdle { assertFalse(view.isImportantForAutofill) }
    }

    @Test
    fun typedTextGoesIntoTheCallersState() {
        val state = TextFieldState()
        content { PasswordField(PasswordKind.WalletPassword, state) }
        compose.onNodeWithTag(PasswordKind.WalletPassword.english).performTextInput("Abc12345!")
        compose.runOnIdle { assertEquals("Abc12345!", state.text.toString()) }
    }

    /**
     * iOS `SecureField(title, …)` keeps its title as the VoiceOver label. Two filled fields ("New password" and
     * "Confirm new password") must stay distinguishable once their placeholders are no longer drawn.
     */
    @Test
    fun aFilledFieldIsStillNamedByItsTitle() {
        content {
            PasswordField(PasswordKind.NewPassword, remember { TextFieldState("Abc12345!") }, isNew = true)
            PasswordField(PasswordKind.ConfirmNewPassword, remember { TextFieldState() }, isNew = true)
        }
        val labels = { node: androidx.compose.ui.semantics.SemanticsNode ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { label -> label.text }
        }
        // The title is a label merged into the field, read after its (masked) text.
        compose.onNodeWithTag(PasswordKind.NewPassword.english)
            .assert(SemanticsMatcher("named New password") { labels(it) == listOf("New password") })
        // Empty: named once, not by the label and the drawn placeholder both.
        compose.onNodeWithTag(PasswordKind.ConfirmNewPassword.english)
            .assert(SemanticsMatcher("named Confirm new password once") { labels(it) == listOf("Confirm new password") })
        // Never a content description: TalkBack reads (and steps through) a description instead of the field's own
        // text, which would make a revealed passphrase impossible to check by ear.
        compose.onNodeWithTag(PasswordKind.NewPassword.english).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }
}

/** A lifecycle the test moves by hand (no lifecycle-runtime-testing dependency needed). */
private class ManualLifecycleOwner : LifecycleOwner {
    val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
    fun handleLifecycleEvent(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
}
