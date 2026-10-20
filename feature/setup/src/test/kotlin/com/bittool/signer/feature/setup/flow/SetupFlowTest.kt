package com.bittool.signer.feature.setup.flow

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.data.testing.FakeSignerStore.Call
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.navigation.ConnectRoute
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.navigation.WalletRoute
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.feature.setup.Phrases
import com.bittool.signer.feature.setup.SeededRandomSource
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.testBip39
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The flow through its real screens (the parts of the iOS UI tests G1.1–G1.7 that run in-process): create with reveal,
 * backup check and password; Back and Cancel; import with the language choice; the passphrase fields.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class SetupFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val store = FakeSignerStore().apply {
        onGenerateMnemonic = { count -> SecretText.of(if (count == 12) Phrases.VECTOR_12 else Phrases.VECTOR_24) }
    }
    private val factory = FakeRecoverySessionFactory()
    private var cancelled = 0
    private val finished = mutableListOf<WalletRoute?>()

    private fun flow(intent: SetupIntent = SetupIntent.Create): WalletSetupViewModel {
        val model = WalletSetupViewModel(intent, store, testBip39, SeededRandomSource(), factory, Dispatchers.Unconfined)
        compose.setContent {
            SetupTestHost(factory) {
                SetupFlow(model, onFinished = { finished += it }, onCancelled = { cancelled++ })
            }
        }
        compose.waitForIdle()
        return model
    }

    private fun assertStep(step: Int, of: Int) {
        compose.onNodeWithText("Step $step of $of").assertExists()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun stateDescription(tag: String): String? =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription) { "" }.ifEmpty { null }

    @Test
    fun createRequiresTheRevealTheBackupCheckAndAPassword() {
        val model = flow()
        assertStep(1, 4)
        compose.onNodeWithTag("setup.name").assert(hasText("My Wallet"))
        compose.onNodeWithText("12 words").performScrollTo().performClick()
        compose.onNodeWithTag("setup.create").performClick()
        assertStep(2, 4)

        compose.onNodeWithTag("recovery.reveal").assertExists()
        compose.onNodeWithTag("recovery.word.0").assertDoesNotExist()
        compose.onNodeWithTag("backup.continue").assertIsNotEnabled()
        compose.onNodeWithTag("recovery.reveal").performScrollTo().performClick()
        compose.onNodeWithText("Screenshot Detected").assertDoesNotExist()
        compose.onNodeWithTag("recovery.word.0").assertExists()
        compose.onNodeWithTag("backup.continue").assertIsEnabled()
        compose.onNodeWithTag("recovery.hide").performScrollTo().performClick()
        compose.onNodeWithTag("recovery.word.0").assertDoesNotExist()

        // Background and return: still step 2, concealed, and the same phrase when revealed again.
        val words = model.uiState.value.words
        compose.runOnUiThread {
            factory.monitor.moveToBackground()
            store.suspendSession()
            factory.monitor.returnToForeground()
        }
        compose.waitForIdle()
        assertStep(2, 4)
        compose.onNodeWithTag("recovery.word.0").assertDoesNotExist()
        compose.onNodeWithTag("recovery.reveal").performScrollTo().performClick()
        compose.onNodeWithTag("recovery.word.0").assert(hasText(words.list[0], substring = true))
        assertEquals(1, store.calls<Call.GenerateMnemonic>().size)

        compose.onNodeWithTag("backup.continue").performClick()
        assertStep(3, 4)
        compose.onNodeWithTag("recovery.word.0").assertDoesNotExist()
        compose.onNodeWithTag("verification.continue").assertIsNotEnabled()
        val verification = assertNotNull(model.uiState.value.verification)
        assertEquals(4, compose.onAllNodes(SemanticsMatcher("blank") { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("verification.slot.") }).fetchSemanticsNodes().size)
        assertEquals("Empty, next to fill", stateDescription("verification.slot.${verification.positions.first()}"))
        for (position in verification.positions) {
            val chip = verification.chips.first { it.word == words.list[position] && model.uiState.value.verification?.isPlaced(it) == false }
            compose.onNodeWithTag("verification.chip.${chip.id}").performClick()
        }
        compose.onNodeWithTag("verification.continue").assertIsEnabled().performClick()
        assertStep(4, 4)

        compose.onNodeWithTag("setup.finish").assertIsNotEnabled()
        compose.onNodeWithTag(PasswordKind.WalletPassword.english).performTextInput("UiTest-Backup8!")
        compose.onNodeWithTag(PasswordKind.ConfirmPassword.english).performTextInput("UiTest-Backup8!")
        compose.onNodeWithTag("setup.finish").assertIsEnabled().performClick()
        compose.onNodeWithText("Wallet Created").assertExists()
        val created = assertNotNull(model.uiState.value.created)
        compose.onNodeWithTag("setup.connect").performClick()
        compose.runOnIdle { assertEquals(listOf<WalletRoute?>(ConnectRoute.walletLevel(created.id)), finished) }
    }

    @Test
    fun backPopsPushedStepsAndTheFormAsksBeforeDiscarding() {
        flow()
        compose.onNodeWithTag("setup.create").performClick()
        assertStep(2, 4)
        back()
        assertStep(1, 4)
        back()
        compose.onNodeWithText("Discard this wallet?").assertExists()
        compose.onNodeWithText("The recovery phrase shown here will be erased. If you already wrote it down, it will not be used.").assertExists()
        compose.onNodeWithTag("setup.keepEditing").performClick()
        compose.onNodeWithText("Discard this wallet?").assertDoesNotExist()
        compose.onNodeWithTag("setup.cancel").performClick()
        compose.onNodeWithTag("setup.discard").performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test
    fun cancellingAnEmptyImportClosesAtOnce() {
        flow(SetupIntent.Restore)
        assertStep(1, 2)
        compose.onNodeWithTag("setup.cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test
    fun anAmbiguousImportAsksWhichLanguageTheBackupIsIn() {
        val model = flow(SetupIntent.Restore)
        compose.onNodeWithTag("setup.import").assertIsNotEnabled()
        compose.onNodeWithTag("recovery.input.visible").performTextInput(Phrases.ITALIAN_OR_SPANISH)
        compose.waitForIdle()
        compose.onNodeWithTag("recovery.status").assert(hasText("12 words", substring = true))
        compose.onNodeWithTag("setup.import").assertIsEnabled().performClick()
        compose.onNodeWithText("Which language is your backup in?").assertExists()
        compose.onNodeWithText("Italiano: nomina solido comodo…").assertExists()
        compose.onNodeWithText("Español: nómina sólido cómodo…").performClick()
        assertStep(2, 2)
        compose.runOnIdle { assertEquals(com.bittool.signer.core.crypto.bip39.Bip39Language.Spanish, model.importedLanguage) }
        compose.onNodeWithText("Which language is your backup in?").assertDoesNotExist()
    }

    @Test
    fun anInvalidImportShowsTheErrorAboveContinue() {
        flow(SetupIntent.Restore)
        compose.onNodeWithTag("recovery.input.visible").performTextInput("abandon abandon abandon")
        compose.waitForIdle()
        compose.onNodeWithTag("recovery.status").assert(hasText("3 words", substring = true))
        compose.onNodeWithTag("setup.import").performClick()
        compose.onNodeWithTag("setup.error").assert(hasText("Invalid recovery phrase", substring = true))
        back()
        compose.onNodeWithText("Discard this wallet?").assertExists()
        compose.onNodeWithText("The words you entered will be erased from this screen.").assertExists()
    }

    /** iOS `PassphraseFieldTests.setupShowsTheEyeOnBothPassphraseFields`. */
    @Test
    fun setupShowsTheEyeOnBothPassphraseFields() {
        flow()
        compose.onNodeWithTag(PasswordKind.Passphrase.english).assertDoesNotExist()
        compose.onNodeWithTag("setup.usePassphrase").performScrollTo().performClick()
        compose.onNodeWithTag(PasswordKind.Passphrase.revealTag).assertExists()
        compose.onNodeWithTag(PasswordKind.ConfirmPassphrase.revealTag).assertExists()
        compose.onNodeWithText("Each passphrase opens a different wallet", substring = true).assertExists()
        // Mismatching passphrases keep "Create Recovery Phrase" disabled.
        compose.onNodeWithTag(PasswordKind.Passphrase.english).performScrollTo().performTextInput("one")
        compose.onNodeWithTag(PasswordKind.ConfirmPassphrase.english).performScrollTo().performTextInput("two")
        compose.onNodeWithTag("setup.create").assertIsNotEnabled()
        compose.onAllNodesWithTag(PasswordKind.ConfirmPassphrase.revealTag).fetchSemanticsNodes().single()
    }

    @Test
    fun backOnTheDoneStepIsDone() {
        val model = flow(SetupIntent.Restore)
        compose.onNodeWithTag("recovery.input.visible").performTextInput(FakeSignerStore.PUBLIC_FIXTURE_12)
        compose.onNodeWithTag("setup.import").performClick()
        assertStep(2, 2)
        compose.onNodeWithTag(PasswordKind.WalletPassword.english).performTextInput("Public-Test-Password1!")
        compose.onNodeWithTag(PasswordKind.ConfirmPassword.english).performTextInput("Public-Test-Password1!")
        compose.onNodeWithTag("setup.finish").performClick()
        compose.onNodeWithText("Wallet Imported").assertExists()
        compose.onNode(hasTestTag("nav.back")).assertDoesNotExist()
        back()
        compose.runOnIdle {
            assertEquals(listOf<WalletRoute?>(null), finished)
            assertNotNull(model.uiState.value.created)
        }
    }
}
