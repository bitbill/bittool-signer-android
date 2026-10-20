package com.bittool.signer.feature.setup.screenshot

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.feature.setup.Look
import com.bittool.signer.feature.setup.Phrases
import com.bittool.signer.feature.setup.SeededRandomSource
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.applySnapshots
import com.bittool.signer.feature.setup.backup.RecoveryCardsPage
import com.bittool.signer.feature.setup.backup.SecurityTipsList
import com.bittool.signer.feature.setup.flow.RecoveryWords
import com.bittool.signer.feature.setup.flow.SetupFlow
import com.bittool.signer.feature.setup.flow.WalletIdentity
import com.bittool.signer.feature.setup.flow.WalletSetupViewModel
import com.bittool.signer.feature.setup.reveal.RecoveryPhraseContent
import com.bittool.signer.feature.setup.reveal.RecoveryPhraseUiState
import com.bittool.signer.feature.setup.reveal.RevealedPhrase
import com.bittool.signer.feature.setup.testBip39
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Records the setup and backup screens to `feature/setup/build/outputs/roborazzi/setup/` for visual QA (record only, no
 * golden comparison; `-Psigner.screenshots=false` skips them): every screen in light and dark, the densest ones also at
 * font scale 2.0 and in Arabic (right to left; the recovery words stay left to right). Phone viewport, published BIP39
 * vectors only.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h880dp-xhdpi")
class SetupScreenshotTest(private val screen: Screen, private val look: Look) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val factory = FakeRecoverySessionFactory()
    private val existing = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)
    private val store = FakeSignerStore().apply {
        onGenerateMnemonic = { count -> SecretText.of(if (count == 12) Phrases.VECTOR_12 else Phrases.VECTOR_24) }
    }

    enum class Screen(val slug: String, val everyLook: Boolean = false) {
        Create("01-create", everyLook = true),
        Import("02-import", everyLook = true),
        BackupConcealed("03-backup-concealed"),
        BackupRevealed("04-backup-revealed", everyLook = true),
        BackupQr("05-backup-qr"),
        Verify("06-verify", everyLook = true),
        Password("07-password"),
        PasswordReset("08-password-reset"),
        Done("09-done"),
        Cards("10-recovery-cards"),
        Tips("11-security-tips"),
        RecoveryPassword("12-recovery-phrase-password"),
        RecoveryRevealed("13-recovery-phrase-revealed"),

        /** A back gesture that closes setup, held at 80 %: the shrinking surface (setup-backup §15.4). */
        ClosePreview("14-close-preview"),
    }

    private fun model(intent: SetupIntent): WalletSetupViewModel =
        WalletSetupViewModel(intent, store, testBip39, SeededRandomSource(), factory, Dispatchers.Unconfined)

    private fun showFlow(model: WalletSetupViewModel) {
        compose.setContent { SetupTestHost(factory, look) { SetupFlow(model, onFinished = {}, onCancelled = {}) } }
        compose.waitForIdle()
    }

    private fun act(block: () -> Unit) {
        compose.runOnUiThread {
            block()
            applySnapshots()
        }
        compose.waitForIdle()
    }

    /** Fills the first [count] blanks correctly, one action at a time (the UI state settles between them). */
    private fun WalletSetupViewModel.answerTheCheck(count: Int) {
        val positions = uiState.value.verification?.positions ?: return
        val words = uiState.value.words.list
        for (position in positions.take(count)) {
            act { select(position) }
            val current = uiState.value.verification!!
            act { place(current.chips.first { it.word == words[position] && !current.isPlaced(it) }.id) }
        }
    }

    @Test
    fun capture() {
        if (look.rtl) RuntimeEnvironment.setQualifiers("+ar")
        when (screen) {
            Screen.Create -> showFlow(model(SetupIntent.Create))
            Screen.ClosePreview -> {
                showFlow(model(SetupIntent.Create))
                compose.runOnUiThread {
                    val back = compose.activity.onBackPressedDispatcher
                    back.dispatchOnBackStarted(BackEventCompat(8f, 600f, 0f, BackEventCompat.EDGE_LEFT))
                    back.dispatchOnBackProgressed(BackEventCompat(240f, 600f, 0.8f, BackEventCompat.EDGE_LEFT))
                }
            }
            Screen.Import -> {
                val model = model(SetupIntent.Restore)
                showFlow(model)
                act { model.fields.phrase.setTextAndPlaceCursorAtEnd(Phrases.SPANISH) }
            }
            Screen.BackupConcealed, Screen.BackupRevealed, Screen.BackupQr -> {
                val model = model(SetupIntent.Create)
                showFlow(model)
                act { model.startCreate() }
                when (screen) {
                    Screen.BackupRevealed -> compose.onNodeWithTag("recovery.reveal").performScrollTo().performClick()
                    Screen.BackupQr -> {
                        compose.onNodeWithText("QR Code").performClick()
                        compose.onNodeWithTag("backup.qr.reveal").performScrollTo().performClick()
                        // The code is encoded off the main thread: record it once it is drawn, not the spinner.
                        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("QR code").fetchSemanticsNodes().isNotEmpty() }
                    }
                    else -> Unit
                }
            }
            Screen.Verify, Screen.Password, Screen.Done -> {
                val model = model(SetupIntent.Create)
                showFlow(model)
                act {
                    model.setWordCount(12)
                    model.startCreate()
                    model.markRevealed()
                    model.confirmBackup()
                }
                if (screen == Screen.Verify) {
                    model.answerTheCheck(2)
                } else {
                    model.answerTheCheck(4)
                    act { model.finishVerification() }
                    act {
                        model.fields.password.setTextAndPlaceCursorAtEnd("UiTest-Backup8!")
                        model.fields.confirmation.setTextAndPlaceCursorAtEnd("UiTest-Backup8")
                    }
                    if (screen == Screen.Done) {
                        act { model.fields.confirmation.setTextAndPlaceCursorAtEnd("UiTest-Backup8!") }
                        act { model.createWallet() }
                    }
                }
            }
            Screen.PasswordReset -> {
                store.setState { it.copy(wallets = listOf(existing)) }
                store.onExistingWallet = { _, _ -> existing.id }
                val model = model(SetupIntent.Restore)
                showFlow(model)
                act { model.fields.phrase.setTextAndPlaceCursorAtEnd(FakeSignerStore.PUBLIC_FIXTURE_12) }
                act { model.startImport() }
                act { model.fields.password.setTextAndPlaceCursorAtEnd("Short1") }
            }
            Screen.Cards -> {
                compose.setContent {
                    SetupTestHost(factory, look) {
                        RecoveryCardsPage(RecoveryWords(Phrases.VECTOR_12.split(' ')), hasPassphrase = true, session = remember { factory.privacySession() }, onReveal = {})
                    }
                }
                compose.onNodeWithTag("cards.reveal").performScrollTo().performClick()
            }
            Screen.Tips -> compose.setContent {
                SetupTestHost(factory, look) { Box(Modifier.padding(vertical = 16.dp)) { SecurityTipsList() } }
            }
            Screen.RecoveryPassword, Screen.RecoveryRevealed -> {
                val identity = WalletIdentity("Public fixture 1", "73C5DA0A", existing.id.uppercase)
                val revealed = if (screen == Screen.RecoveryRevealed) RevealedPhrase(RecoveryWords(Phrases.VECTOR_12.split(' ')), "TREZOR") else null
                compose.setContent {
                    SetupTestHost(factory, look) {
                        RecoveryPhraseContent(
                            state = RecoveryPhraseUiState(identity = identity, revealed = revealed),
                            password = remember { TextFieldState() },
                            sessions = factory,
                            onUnlock = {},
                            onClose = {},
                        )
                    }
                }
                if (revealed != null) compose.onNodeWithTag("recovery.reveal").performScrollTo().performClick()
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/setup/${screen.slug}-${look.name.lowercase()}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> = Screen.entries.flatMap { screen ->
            val looks = if (screen.everyLook) Look.entries else listOf(Look.Light, Look.Dark)
            looks.map { arrayOf<Any>(screen, it) }
        }
    }
}
