package com.bittool.signer.feature.setup.flow

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineBreak
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.data.ExistingWalletMatch
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.data.testing.FakeSignerStore.Call
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.navigation.WalletDetailRoute
import com.bittool.signer.core.navigation.WalletRoute
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.feature.setup.SeededRandomSource
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.testBip39
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The password step's QA round 1 fixes (setup-08, setup-09, setup-15, backlog B21), on the real screens. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class PasswordStepTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val store = FakeSignerStore()
    private val factory = FakeRecoverySessionFactory()
    private val existing = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)
    private val finished = mutableListOf<WalletRoute?>()

    /** Import of the public fixture phrase, up to the password step. */
    private fun passwordStep(): WalletSetupViewModel {
        val model = WalletSetupViewModel(SetupIntent.Restore, store, testBip39, SeededRandomSource(), factory, Dispatchers.Unconfined, InMemoryDeviceKeyStore())
        compose.setContent { SetupTestHost(factory) { SetupFlow(model, onFinished = { finished += it }, onCancelled = {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag("recovery.input.visible").performTextInput(FakeSignerStore.PUBLIC_FIXTURE_12)
        compose.onNodeWithTag("setup.import").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Step 2 of 2").assertExists()
        return model
    }

    private fun typePasswords() {
        compose.onNodeWithTag(PasswordKind.WalletPassword.english).performTextInput("Public-Test-Password1!")
        compose.onNodeWithTag(PasswordKind.ConfirmPassword.english).performTextInput("Public-Test-Password1!")
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** QA setup-08: no "Import Wallet" that a quick tap could press before the page turns into Reset Password. */
    @Test
    fun theActionWaitsUntilTheDuplicateCheckAnswers() {
        store.setState { it.copy(wallets = listOf(existing)) }
        val gate = CompletableDeferred<WalletId?>()
        store.onExistingWallet = { _, _ -> gate.await() }
        passwordStep()
        compose.onNodeWithTag("setup.finish").assertIsNotEnabled()
        typePasswords()
        // Valid passwords, no answer yet: the action is held and says so with its spinner.
        compose.onNodeWithTag("setup.finish").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
        compose.onNodeWithTag("setup.finish").performClick()
        compose.runOnIdle { assertTrue(store.calls<Call.CreateWallet>().isEmpty()) }

        gate.complete(existing.id)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Reset Password", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("setup.finish").assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        compose.runOnIdle { assertTrue(store.calls<Call.CreateWallet>().isEmpty()) }
    }

    /** QA setup-09: the back arrow stays (disabled) while saving, so the title keeps its place. */
    @Test
    fun theBackArrowStaysInPlaceWhileSaving() {
        val saving = CompletableDeferred<Unit>()
        val create = store.onCreateWallet
        store.onCreateWallet = { name, mnemonic, passphrase, password ->
            saving.await()
            create(name, mnemonic, passphrase, password)
        }
        passwordStep()
        typePasswords()
        val titleBefore = compose.onNodeWithText("Password").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("nav.back").assertIsEnabled()
        compose.onNodeWithTag("setup.finish").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("nav.back").assertExists().assertIsNotEnabled()
        assertEquals(titleBefore, compose.onNodeWithText("Password").fetchSemanticsNode().boundsInRoot, "the title does not move")
        saving.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("Wallet Imported").assertExists()
    }

    /** QA backlog B21: a lost device key is explained, with the way to the wallet's removal, instead of a reset. */
    @Test
    fun aPhraseWhoseWalletLostItsDeviceKeyLeadsToThatWallet() {
        store.setState { it.copy(wallets = listOf(existing)) }
        store.onExistingWallet = { _, _ -> existing.id }
        store.lostDeviceKeys = setOf(existing.id)
        passwordStep()
        compose.onNodeWithText("This Wallet Can’t Be Unlocked", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Remove This Wallet, and import the phrase again", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Public fixture 1").assertExists()
        compose.onNodeWithTag(PasswordKind.WalletPassword.english).assertDoesNotExist()
        compose.onNodeWithTag("setup.finish").assertDoesNotExist()

        compose.onNodeWithTag(OPEN_LOST_KEY_WALLET_TAG).performClick()
        compose.runOnIdle {
            assertEquals(listOf<WalletRoute?>(WalletDetailRoute(existing.id)), finished)
            assertTrue(store.calls<Call.ResetPassword>().isEmpty())
        }
    }

    /**
     * QA L3: a phrase whose wallet here only an unauthenticated sidecar names (its public data needs repair, its key is
     * fine) is led to that wallet's Repair: no password form, no "Import Wallet" that would add a second wallet.
     */
    @Test
    fun aPhraseWhoseWalletNeedsRepairLeadsToThatWalletInsteadOfASecondOne() {
        store.setState { it.copy(wallets = listOf(existing)) }
        store.onFindExistingWallet = { _, _ -> ExistingWalletMatch(existing.id, verified = false) }
        passwordStep()
        compose.onNodeWithText("This Wallet Needs Repair", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("choose Repair Wallet Data with its password", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(PasswordKind.WalletPassword.english).assertDoesNotExist()
        compose.onNodeWithTag("setup.finish").assertDoesNotExist()

        compose.onNodeWithTag(OPEN_LOST_KEY_WALLET_TAG).performClick()
        compose.runOnIdle {
            assertEquals(listOf<WalletRoute?>(WalletDetailRoute(existing.id)), finished)
            assertTrue(store.calls<Call.CreateWallet>().isEmpty())
            assertTrue(store.calls<Call.ResetPassword>().isEmpty())
        }
    }

    /** QA setup-15: large titles break into balanced lines, at phrase boundaries in Japanese on API 33+. */
    @Test
    fun largeTitlesUseHeadingLineBreaks() {
        passwordStep()
        assertEquals(LineBreak.Heading, compose.onNodeWithText("Set a Password", useUnmergedTree = true).textLayout().layoutInput.style.lineBreak)
        typePasswords()
        compose.onNodeWithTag("setup.finish").performClick()
        compose.waitForIdle()
        assertEquals(LineBreak.Heading, compose.onNodeWithTag("setup.doneTitle").textLayout().layoutInput.style.lineBreak)
    }
}
