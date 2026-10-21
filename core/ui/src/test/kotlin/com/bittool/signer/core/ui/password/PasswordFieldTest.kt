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
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.autofill.FillableData
import androidx.compose.ui.autofill.createFromText
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.input.ImeAction
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
     * QA setup-01 / wallets-01 (backlog B1 re-verified): the round 1 device was API 31, where there is no
     * accessibility-data-sensitive filter (API 34+), so every service, `uiautomator dump` included, gets the node. Its
     * text must still be the bullets, for the wallet password and for a hidden passphrase alike.
     */
    @Test
    @Config(sdk = [32])
    fun beforeApi34EveryServiceGetsBulletsOnly() {
        val password = TextFieldState()
        val passphrase = TextFieldState()
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Column {
                    PasswordField(PasswordKind.WalletPassword, password)
                    PasswordField(PasswordKind.Passphrase, passphrase, revealable = true)
                }
            }
        }
        for ((kind, state) in listOf(PasswordKind.WalletPassword to password, PasswordKind.Passphrase to passphrase)) {
            val field = compose.onNodeWithTag(kind.english)
            field.performTextInput(SECRET)
            compose.runOnIdle { assertEquals(SECRET, state.text.toString(), "typing still reaches the state") }
            val id = field.fetchSemanticsNode().id
            compose.runOnIdle {
                val info = assertNotNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(id), "${kind.english}: the node exists")
                assertTrue(info.isPassword, "${kind.english}: a password node")
                // Checked without printing the node's text (ARCHITECTURE §4.9).
                assertTrue(info.text?.toString() == "•".repeat(SECRET.length), "${kind.english}: one bullet per character")
                assertFalse(info.text.toString().contains(SECRET), "${kind.english}: the typed secret is not in the node")
            }
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

    /**
     * Backlog B27: besides its (masked) text, a text field publishes what was typed as its input text and its autofill
     * value, and accepts autofill data. For a secret field none of that carries the secret, typed or revealed: the node
     * is not fillable, both carry the bullets, and filling is refused.
     */
    @Test
    fun autofillSemanticsNeverCarryTheSecret() {
        val state = TextFieldState()
        content { PasswordField(PasswordKind.Passphrase, state, revealable = true) }
        val field = compose.onNodeWithTag(PasswordKind.Passphrase.english)
        field.performTextInput(SECRET)
        for (revealed in listOf(false, true)) {
            if (revealed) compose.onNodeWithTag(PasswordKind.Passphrase.revealTag).performClick()
            val config = field.fetchSemanticsNode().config
            val mask = "•".repeat(SECRET.length)
            assertEquals(ContentDataType.None, config[SemanticsProperties.ContentDataType], "not fillable (revealed: $revealed)")
            assertTrue(config[SemanticsProperties.InputText].text == mask, "input text is the bullets (revealed: $revealed)")
            val fillable = config.getOrNull(SemanticsProperties.FillableData)
            assertTrue(fillable == null || fillable.textValue?.toString() == mask, "autofill value is the bullets (revealed: $revealed)")
            val fill = config[SemanticsActions.OnFillData].action
            assertFalse(assertNotNull(fill).invoke(assertNotNull(FillableData.createFromText("Autofilled1!"))), "filling is refused")
        }
        compose.runOnIdle { assertEquals(SECRET, state.text.toString(), "an autofill attempt changed nothing") }
    }

    /**
     * QA setup-04 (iOS `WalletSetupFlow.swift:263`, `onSubmit { focus = .confirmation }`): "Next" on the passphrase moves
     * to "Confirm passphrase". In the keyboard input mode (a hardware keyboard, an IME Enter key event) the field's own
     * eye button is focusable too, and the default "Next" landed on it, which took the keyboard away.
     */
    @Test
    fun nextOnThePassphraseMovesToItsConfirmationNotOntoTheEye() {
        lateinit var inputMode: InputModeManager
        content {
            inputMode = LocalInputModeManager.current
            PasswordField(PasswordKind.Passphrase, remember { TextFieldState() }, revealable = true, imeAction = ImeAction.Next)
            PasswordField(PasswordKind.ConfirmPassphrase, remember { TextFieldState() }, revealable = true)
        }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, inputMode.inputMode, "precondition: buttons are focusable") }
        val passphrase = compose.onNodeWithTag(PasswordKind.Passphrase.english)
        passphrase.performSemanticsAction(SemanticsActions.RequestFocus)
        passphrase.assertIsFocused()
        passphrase.performImeAction()
        compose.onNodeWithTag(PasswordKind.ConfirmPassphrase.english).assertIsFocused()
        compose.onNodeWithTag(PasswordKind.Passphrase.revealTag).assertIsNotFocused()
        // The eye is still a focus target of its own for keyboard navigation (Tab from the field reaches it).
        compose.onNodeWithTag(PasswordKind.ConfirmPassphrase.revealTag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag(PasswordKind.ConfirmPassphrase.revealTag).assertIsFocused()
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
