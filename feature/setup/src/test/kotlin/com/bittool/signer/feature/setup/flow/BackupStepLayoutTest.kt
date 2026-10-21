package com.bittool.signer.feature.setup.flow

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA regression-08: at font scale 2.0 the backup step's pinned bar (Security Tips and a two-line "I’ve Written Them Down
 * in Order") took about 45 % of the height, and the revealed words scrolled through a slot one row high. With
 * accessibility text sizes, and on short windows, Security Tips now scrolls with the page under the panel; at the
 * default size on a phone it stays in the bar, as on iOS (`WalletSetupFlow.swift:295-307`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w393dp-h807dp-xhdpi")
class BackupStepLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val store = FakeSignerStore().apply { onGenerateMnemonic = { SecretText.of(Phrases.VECTOR_24) } }
    private val factory = FakeRecoverySessionFactory()

    private fun backupStep(look: Look) {
        val model = WalletSetupViewModel(SetupIntent.Create, store, testBip39, SeededRandomSource(), factory, Dispatchers.Unconfined, InMemoryDeviceKeyStore())
        compose.setContent { SetupTestHost(factory, look) { SetupFlow(model, onFinished = {}, onCancelled = {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag("setup.create").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("recovery.reveal").performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Whether [node] lies inside the page's vertical scroll (as opposed to the pinned bottom bar). */
    private fun scrollsWithThePage(node: SemanticsNode): Boolean {
        var parent = node.parent
        while (parent != null) {
            if (SemanticsProperties.VerticalScrollAxisRange in parent.config) return true
            parent = parent.parent
        }
        return false
    }

    private fun tips() = compose.onNodeWithTag("backup.tips", useUnmergedTree = true).fetchSemanticsNode()

    @Test
    fun atAccessibilityTextSizesSecurityTipsLeavesTheBarForThePage() {
        backupStep(Look.FontScale2)
        assertTrue(scrollsWithThePage(tips()), "Security Tips scrolls with the page at font scale 2")
        // It comes after the backup panel (and its Recovery Cards link), and still opens the tips.
        val cards = compose.onNodeWithTag("backup.cards").unclippedBounds()
        val tips = compose.onNodeWithTag("backup.tips").unclippedBounds()
        assertTrue(tips.top >= cards.bottom, "Security Tips ($tips) comes after the panel ($cards)")
        compose.onNodeWithTag("backup.tips").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tips.done").assertExists()
    }

    @Test
    fun atTheDefaultSizeSecurityTipsStaysInTheBar() {
        backupStep(Look.Light)
        assertFalse(scrollsWithThePage(tips()), "Security Tips is pinned above the button, as on iOS")
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-xhdpi")
    fun onAShortWindowSecurityTipsLeavesTheBarForThePage() {
        backupStep(Look.Light)
        assertTrue(scrollsWithThePage(tips()), "Security Tips scrolls with the page in landscape")
    }
}
