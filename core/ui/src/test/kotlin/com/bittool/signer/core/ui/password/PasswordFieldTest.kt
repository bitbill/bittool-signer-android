package com.bittool.signer.core.ui.password

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.designsystem.security.LocalSensitiveDataMarking
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        // Hidden: the node is sensitive and reports bullets only.
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsSensitiveData, true))
        field.assert(editableTextIs(maskForAccessibility("correct horse".length)))
        eye.performClick()
        eye.assertContentDescriptionEquals("Hide secret")
        compose.runOnIdle { assertTrue(view.isAccessibilityDataSensitive, "a visible passphrase is accessibility-sensitive") }
        // Shown: read as drawn, and still only by accessibility tools (TalkBack) — node and view are both sensitive.
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsSensitiveData, true))
        field.assert(editableTextIs("correct horse"))
        compose.runOnIdle { lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        eye.assertContentDescriptionEquals("Show secret")
        compose.runOnIdle { assertFalse(view.isAccessibilityDataSensitive) }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsSensitiveData, true))
        field.assert(editableTextIs(maskForAccessibility("correct horse".length)))
        assertEquals("correct horse", state.text.toString(), "hiding keeps what was typed")
    }

    /**
     * Backlog B1: `uiautomator dump` printed a typed password, because `BasicSecureTextField` reports its plain text to
     * accessibility. The field now behaves like a platform password `EditText`: password semantics, accessibility-data-
     * sensitive (API 34+: services that are not accessibility tools get no node at all), and bullets as its text for the
     * tools that do (TalkBack, UiAutomator) — what was typed is never in the node.
     */
    @Test
    fun aTypedPasswordNeverReachesAccessibility() {
        val state = TextFieldState()
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) { PasswordField(PasswordKind.WalletPassword, state) }
        }
        val field = compose.onNodeWithTag(PasswordKind.WalletPassword.english)
        field.performTextInput(SECRET)
        compose.runOnIdle { assertEquals(SECRET, state.text.toString(), "typing still reaches the state") }
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.IsSensitiveData, true))
            .assert(editableTextIs("•".repeat(SECRET.length)))
        val id = field.fetchSemanticsNode().id
        // An ordinary accessibility service (not a tool): the node does not exist for it.
        compose.runOnIdle { assertNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(id), "hidden from non-tools") }
        // An accessibility tool (what UiAutomator is on API 34+): the node, its id and bounds, but bullets as its text.
        asAccessibilityTool(true)
        try {
            compose.runOnIdle {
                val info = assertNotNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(id))
                assertTrue(info.isPassword)
                // Checked without printing the node's text (ARCHITECTURE §4.9).
                assertTrue(info.text?.toString() == "•".repeat(SECRET.length), "the node reports one bullet per character")
                assertFalse(info.text.toString().contains(SECRET), "the typed password is not in the node")
            }
        } finally {
            asAccessibilityTool(false)
        }
    }

    /**
     * The debug UI-test switch of gaps G5.3 (`LocalSensitiveDataMarking`) covers the field's own node marking too, so a
     * harness that has to turn the marking off is not left with password fields its UiAutomator cannot see. The bullets
     * do not depend on it: what was typed never reaches accessibility.
     */
    @Test
    fun theSensitiveMarkingSwitchCoversTheNodeButNeverTheMask() {
        val state = TextFieldState()
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalSensitiveDataMarking provides false) {
                    PasswordField(PasswordKind.WalletPassword, state)
                }
            }
        }
        val field = compose.onNodeWithTag(PasswordKind.WalletPassword.english)
        field.performTextInput(SECRET)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsSensitiveData, false))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(editableTextIs("•".repeat(SECRET.length)))
    }

    /**
     * Backlog B2: `clearText()` records the deletion as an undoable edit, so Ctrl+Z typed a wiped password back. A
     * password field keeps no undo history and ignores the undo / redo shortcuts.
     */
    @OptIn(ExperimentalFoundationApi::class, ExperimentalTestApi::class)
    @Test
    fun aClearedPasswordCannotBeBroughtBackByUndo() {
        val state = TextFieldState()
        content { PasswordField(PasswordKind.WalletPassword, state) }
        val field = compose.onNodeWithTag(PasswordKind.WalletPassword.english)
        field.performTextInput(SECRET)
        compose.runOnIdle { assertFalse(state.undoState.canUndo, "typing leaves no history") }
        compose.runOnIdle { state.clearText() }
        compose.runOnIdle { assertFalse(state.undoState.canUndo, "the caller's clearText leaves no history") }
        field.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) }
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } }
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Y) }
        }
        compose.runOnIdle { assertEquals("", state.text.toString(), "undo brings nothing back") }
    }

    @Test
    fun undoShortcutsAreRecognised() {
        fun key(code: Int, meta: Int) = KeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, code, 0, meta))
        assertTrue(isUndoShortcut(key(android.view.KeyEvent.KEYCODE_Z, android.view.KeyEvent.META_CTRL_ON)))
        assertTrue(isUndoShortcut(key(android.view.KeyEvent.KEYCODE_Z, android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON)))
        assertTrue(isUndoShortcut(key(android.view.KeyEvent.KEYCODE_Y, android.view.KeyEvent.META_CTRL_ON)))
        assertTrue(isUndoShortcut(key(android.view.KeyEvent.KEYCODE_Z, android.view.KeyEvent.META_META_ON)))
        assertFalse(isUndoShortcut(key(android.view.KeyEvent.KEYCODE_Z, 0)), "typing a z")
        assertFalse(isUndoShortcut(key(android.view.KeyEvent.KEYCODE_V, android.view.KeyEvent.META_CTRL_ON)), "paste stays")
    }

    /** Backlog B2: the selection menu keeps Paste and Select All; nothing hands the secret to the clipboard or an app. */
    @Test
    fun theTextMenuOffersPasteAndSelectAllOnly() {
        content { PasswordField(PasswordKind.Passphrase, remember { TextFieldState() }, revealable = true) }
        val filters = compose.onNodeWithTag(PasswordKind.Passphrase.english).fetchSemanticsNode().layoutInfo.getModifierInfo()
            .mapNotNull { it.modifier as? InspectableValue }
            .filter { it.nameFallback == "filterTextContextMenuDataComponents" }
            .map { element ->
                @Suppress("UNCHECKED_CAST")
                element.inspectableElements.first { it.name == "filter" }.value as (TextContextMenuComponent) -> Boolean
            }
        fun offers(key: Any) = filters.all { filter -> filter(TextContextMenuItem(key, label = "item") {}) }
        assertTrue(filters.isNotEmpty(), "the field carries the secret menu filter")
        assertTrue(offers(TextContextMenuKeys.PasteKey))
        assertTrue(offers(TextContextMenuKeys.SelectAllKey))
        for (key in listOf(TextContextMenuKeys.CopyKey, TextContextMenuKeys.CutKey, TextContextMenuKeys.AutofillKey, Any())) {
            assertFalse(offers(key), "no $key")
        }
    }

    /** Ctrl+Z after [wipe]: also a state whose field is not on screen loses its history at once. */
    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun wipeEmptiesTheTextAndItsHistory() {
        val state = TextFieldState()
        state.edit { append(SECRET) }
        assertTrue(state.undoState.canUndo)
        state.wipe()
        assertEquals("", state.text.toString())
        assertFalse(state.undoState.canUndo)
        state.undoState.undo()
        assertEquals("", state.text.toString())
    }

    private fun editableTextIs(expected: String) =
        SemanticsMatcher("reports \"$expected\" as its text") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text == expected
        }

    /** What `AccessibilityInteractionController` sets per request: whether it comes from an `isAccessibilityTool` service. */
    private fun asAccessibilityTool(on: Boolean) {
        val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(AccessibilityManager::class.java)
        AccessibilityManager::class.java.getMethod("setRequestFromAccessibilityTool", Boolean::class.javaPrimitiveType).invoke(manager, on)
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

/** A public test password (gaps.md G1.0 constants style); never a real secret. */
private const val SECRET = "Abc12345!"

/** A lifecycle the test moves by hand (no lifecycle-runtime-testing dependency needed). */
private class ManualLifecycleOwner : LifecycleOwner {
    val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
    fun handleLifecycleEvent(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
}
