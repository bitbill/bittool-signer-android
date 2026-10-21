package com.bittool.signer.feature.wallets

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PublicAccount
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.feature.wallets.addresses.AddressesActions
import com.bittool.signer.feature.wallets.addresses.AddressesContent
import com.bittool.signer.feature.wallets.addresses.AddressesUiState
import com.bittool.signer.feature.wallets.common.WalletIdentity
import com.bittool.signer.feature.wallets.list.WalletsActions
import com.bittool.signer.feature.wallets.list.WalletsContent
import com.bittool.signer.feature.wallets.list.WalletsUiState
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Backlog B29 (the rest of B26): the "Add Wallet" menu and an address row's long-press menu are popup windows of their
 * own, so the activity root's `testTagsAsResourceId` does not reach them and UiAutomator could not find their items
 * (QA round 2: "the menus still have no resource ids"). As `SignerDropdownMenu`s they publish the tags in debuggable
 * builds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp-xhdpi")
class MenuTestTagsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun debuggable() = setDebuggable(true)

    @After
    fun release() = setDebuggable(false)

    private fun content(body: @Composable () -> Unit) = compose.setContent {
        SignerTheme(darkTheme = false, applySystemBarAppearance = false) { body() }
    }

    @Test
    fun theAddWalletMenuPublishesItsItemTags() {
        val state = WalletFixtures.state()
        val intents = mutableListOf<SetupIntent>()
        content {
            WalletsContent(
                WalletsUiState(state.wallets.mapNotNull { WalletIdentity.of(state, it.id) }, loaded = true),
                WalletsActions(onAddWallet = { intents += it }),
            )
        }
        compose.onNodeWithTag("wallets.add").performClick()
        publishesResourceId("wallets.add.create").assertExists()
        publishesResourceId("wallets.add.import").assertExists().performClick()
        assertEquals(listOf(SetupIntent.Restore), intents)
    }

    @Test
    fun theAddressMenuPublishesItsItemTags() {
        val accounts = WalletFixtures.accounts(Chain.Bitcoin, 0, 3)
        val shown = mutableListOf<PublicAccount>()
        content {
            AddressesContent(
                AddressesUiState(chain = Chain.Bitcoin, accounts = accounts, loaded = true),
                AddressesActions(onShowAddress = { shown += it }),
            )
        }
        compose.onNodeWithTag("address.row.1").performTouchInput { longClick() }
        publishesResourceId("address.copy").assertExists()
        publishesResourceId("address.showQR").assertExists().performClick()
        assertEquals(listOf(accounts[1]), shown)
    }

    /** The node tagged [tag], if Compose maps its tag to a resource id: the nearest flag on it or an ancestor is true. */
    private fun publishesResourceId(tag: String) = compose.onNode(hasTestTag(tag) and publishedAsResourceId, useUnmergedTree = true)

    private val publishedAsResourceId = SemanticsMatcher("publishes its test tag as a resource id") { node ->
        generateSequence(node) { it.parent }
            .firstOrNull { SemanticsPropertiesAndroid.TestTagsAsResourceId in it.config }
            ?.config?.get(SemanticsPropertiesAndroid.TestTagsAsResourceId) == true
    }

    private fun setDebuggable(on: Boolean) {
        val info = RuntimeEnvironment.getApplication().applicationInfo
        info.flags = if (on) info.flags or ApplicationInfo.FLAG_DEBUGGABLE else info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }
}
