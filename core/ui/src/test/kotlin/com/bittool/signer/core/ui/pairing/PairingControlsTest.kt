package com.bittool.signer.core.ui.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.AccountProfile
import com.bittool.signer.core.model.AccountProfiles
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.CompatibleWallets
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Connect / Addresses controls (wallets-pairing.md §3.6–§3.8, §3.10). */
@RunWith(RobolectricTestRunner::class)
class PairingControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun content(fontScale: Float = 1f, body: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { Column { body() } }
            }
        }
    }

    @Test
    fun indexEditsKeepDigitsAndClampToTheLargestIndex() {
        assertEquals(AddressIndexInput.Edit("12", 12), AddressIndexInput.edit("a1b2"))
        assertEquals(AddressIndexInput.Edit("", null), AddressIndexInput.edit("abc"))
        assertEquals(AddressIndexInput.Edit("007", 7), AddressIndexInput.edit("007"))
        assertEquals(AddressIndexInput.Edit("1234567890", 1_234_567_890), AddressIndexInput.edit("12345678901"))
        assertEquals(AddressIndexInput.Edit("2147483647", 2_147_483_647), AddressIndexInput.edit("9999999999"))
        // Only ASCII digits: an Arabic-Indic digit is not an index digit.
        assertEquals(AddressIndexInput.Edit("5", 5), AddressIndexInput.edit("٣5"))
        assertEquals(0, AddressIndexInput.step(0, -1))
        assertEquals(AddressIndexInput.MAX, AddressIndexInput.step(AddressIndexInput.MAX, +1))
        assertEquals(42, AddressIndexInput.step(41, +1))
    }

    @Test
    fun theIndexControlAppliesEditsAndSteps() {
        var index by mutableLongStateOf(0L)
        content { AddressIndexControl(index, { index = it }, AccountProfiles.TRON_PATH_PREFIX) }
        compose.onNodeWithTag("connect.tronIndex.field").performTextReplacement("x41")
        compose.runOnIdle { assertEquals(41, index) }
        compose.onNodeWithContentDescription("Increase").performClick()
        compose.runOnIdle { assertEquals(42, index) }
        compose.onNodeWithContentDescription("Derivation path m/44'/195'/0'/0/42").assertExists()
        compose.onNodeWithContentDescription("Decrease").performClick()
        compose.runOnIdle { assertEquals(41, index) }
        compose.onNodeWithTag("connect.tronIndex.field").performTextReplacement("99999999999")
        compose.runOnIdle { assertEquals(AddressIndexInput.MAX, index) }
        compose.onNodeWithContentDescription("Increase").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
    }

    /**
     * Holding a stepper button repeats (iOS `Stepper`), stops at the bound without reporting the same index again (each
     * report is a new pairing request), and the pressed highlight ends with the finger even though the button became
     * disabled under it.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun holdingAStepperRepeatsAndStopsCleanlyAtTheBound() {
        var index by mutableLongStateOf(3L)
        val reported = mutableListOf<Long>()
        content {
            AddressIndexControl(index, { reported += it; index = it }, AccountProfiles.TRON_PATH_PREFIX)
        }
        val decrease = compose.onNodeWithContentDescription("Decrease")
        val idle = decrease.captureToImage().toPixelMap()
        val probe = idle.width / 6 to idle.height / 2 // inside the circle, clear of the glyph
        compose.mainClock.autoAdvance = false
        decrease.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(2_000)
        decrease.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf(2L, 1L, 0L), reported, "one step per repeat, nothing past or at the bound")
        decrease.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        val after = decrease.captureToImage().toPixelMap()
        assertEquals(idle[probe.first, probe.second], after[probe.first, probe.second], "the pressed highlight is gone")
        // The other button still works.
        compose.onNodeWithContentDescription("Increase").performClick()
        compose.runOnIdle { assertEquals(1L, index) }
    }

    @Test
    fun theChainPickerSelectsByTileAndReadsLongNames() {
        var chain by mutableStateOf(Chain.Bitcoin)
        content { ChainPicker(chain, { chain = it }) }
        compose.onNodeWithTag("chain.bitcoin").assertIsSelected().assertContentDescriptionEquals("Bitcoin")
        compose.onNodeWithTag("chain.evm").assertIsNotSelected().assertContentDescriptionEquals("Ethereum & EVM")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab))
        compose.onNodeWithTag("chain.tron").performClick()
        compose.runOnIdle { assertEquals(Chain.Tron, chain) }
        compose.onNodeWithTag("chain.tron").assertIsSelected()
        compose.onNodeWithText("TRON", useUnmergedTree = true).assertExists()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }

    @Test
    fun chainNamesGiveWayAtAccessibilitySizes() {
        content(fontScale = 2f) { ChainPicker(Chain.Bitcoin, {}) }
        compose.onNodeWithText("Solana", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("chain.solana").assertContentDescriptionEquals("Solana")
    }

    @Test
    fun theProfileMenuOpensAndPicksAnOption() {
        var selection by mutableStateOf(AccountProfiles.bitcoinSingle.first())
        content {
            ProfileMenu(
                title = "Address type",
                options = AccountProfiles.bitcoinSingle,
                selection = selection,
                label = AccountProfile::title,
                detail = AccountProfile::detail,
                onSelect = { selection = it },
            )
        }
        compose.onNodeWithTag("profile.menu")
            .assertContentDescriptionEquals("Address type")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Native SegWit, BIP84 · bc1q…"))
            .performClick()
        compose.onNodeWithText("Taproot").performClick()
        compose.runOnIdle { assertEquals("taproot", selection.id) }
    }

    @Test
    fun walletGroupsUseAdaptiveColumns() {
        assertEquals(2, adaptiveColumns(width = 313, minimum = 140, gap = 10))
        assertEquals(1, adaptiveColumns(width = 313, minimum = 260, gap = 10))
        assertEquals(1, adaptiveColumns(width = 50, minimum = 140, gap = 10))
        assertEquals(4, adaptiveColumns(width = 600, minimum = 140, gap = 10))
    }

    @Test
    fun intendedWalletsListSigningAndWatchOnlyWallets() {
        var submitted = 0
        content {
            IntendedWallets(CompatibleWallets.wallets(Chain.Tron, "standard", "address"), onSubmitWallet = { submitted++ }, chain = Chain.Tron)
        }
        compose.onNodeWithText("Watch only", substring = true).assertExists()
        compose.onNodeWithText("TronLink", substring = true).assertExists()
        compose.onNodeWithText("No mainstream wallet currently sends TRON transactions", substring = true).assertExists()
        // The card is taller than the test window: act through semantics rather than a touch.
        compose.onNodeWithTag("connect.submitWallet").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, submitted)
    }
}
