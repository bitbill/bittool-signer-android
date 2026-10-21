package com.bittool.signer.feature.setup.flow

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.feature.setup.Look
import com.bittool.signer.feature.setup.Phrases
import com.bittool.signer.feature.setup.SeededRandomSource
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.testBip39
import com.bittool.signer.feature.setup.unclippedBounds
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA setup-14: at font scale 2.0 the backup check's footer (chips, Confirm, "Show the Phrase Again") takes about half
 * the height and the board starts below the fold, so the first chip filled a blank nobody could see. The first blank
 * is now centred on appearance when it is off screen (iOS centres only on change). Robolectric's fallback font is
 * narrower than the device's (shorter header and footer), so a 480 dp window puts the first blank below the fold here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w393dp-h480dp-xhdpi")
class VerifyCenteringTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val store = FakeSignerStore().apply { onGenerateMnemonic = { SecretText.of(Phrases.VECTOR_24) } }
    private val factory = FakeRecoverySessionFactory()

    @Test
    fun theFirstBlankIsOnScreenWhenTheCheckAppearsAtLargeText() {
        val model = WalletSetupViewModel(SetupIntent.Create, store, testBip39, SeededRandomSource(), factory, Dispatchers.Unconfined, InMemoryDeviceKeyStore())
        compose.setContent { SetupTestHost(factory, Look.FontScale2) { SetupFlow(model, onFinished = {}, onCancelled = {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag("setup.create").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("recovery.reveal").performScrollTo().performClick()
        compose.onNodeWithTag("backup.continue").performClick()
        compose.waitForIdle()

        val active = assertNotNull(model.uiState.value.verification?.activePosition)
        val blank = compose.onNodeWithTag("verification.slot.$active").unclippedBounds()
        val chipsTop = compose.onAllNodes(SemanticsMatcher("chip") { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("verification.chip.") })
            .fetchSemanticsNodes().minOf { it.positionInRoot.y }
        val barTop = chipsTop - with(compose.density) { 8.dp.toPx() }
        val topBarBottom = compose.onNodeWithTag("nav.back").unclippedBounds().bottom
        assertTrue(blank.top >= topBarBottom && blank.bottom <= barTop, "the first blank ($blank) is off screen (visible: $topBarBottom..$barTop)")
    }
}
