package com.bittool.signer.core.ui.pairing

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
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
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.AccountProfile
import com.bittool.signer.core.model.AccountProfiles
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.CompatibleWallets
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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

    /**
     * QA wallets-03: "Address index" was cut without an ellipsis ("Address" at 1.3×, "アドレスインデック" in Japanese),
     * squeezed beside the field and the stepper. iOS gives the label its full width (`layoutPriority(1)`): it stays on
     * one line beside the controls when it fits, and otherwise moves above them, never clipped.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theIndexLabelIsNeverClipped() {
        var fontScale by mutableStateOf(1f)
        var width by mutableStateOf(361.dp)
        compose.setContent {
            val density = LocalDensity.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Column(Modifier.width(width)) { AddressIndexControl(7, {}, AccountProfiles.TRON_PATH_PREFIX) }
                }
            }
        }
        /** Lays the label out and checks that every character is on a line drawn inside its node. */
        fun wholeLabel(what: String): Pair<TextLayoutResult, androidx.compose.ui.geometry.Rect> {
            val node = compose.onNodeWithText("Address index", useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            val last = layout.lineCount - 1
            assertEquals("Address index".length, layout.getLineEnd(last), "$what: every character is on a line")
            assertTrue((0..last).all { layout.getLineRight(it) <= node.size.width + 1f }, "$what: no line is cut at the end")
            assertTrue(layout.getLineBottom(last) <= node.size.height + 1f, "$what: no line is cut at the bottom")
            return layout to node.boundsInRoot
        }
        val field = { compose.onNodeWithTag("connect.tronIndex.field").fetchSemanticsNode().boundsInRoot }
        // A phone at the default size: one line, beside the field.
        val (normal, normalBounds) = wholeLabel("1.0")
        assertEquals(1, normal.lineCount)
        assertTrue(normalBounds.top < field().bottom && normalBounds.bottom > field().top, "beside the field at 1.0")
        // 1.3× on a 320 dp card: no room beside the controls, so above them, whole.
        fontScale = 1.3f
        width = 320.dp
        compose.waitForIdle()
        val (_, largeBounds) = wholeLabel("1.3")
        assertTrue(largeBounds.bottom <= field().top + 1f, "above the field at 1.3")
    }

    /**
     * QA wallets-11: iOS `.numberPad` is digits only; Android's number keyboard also has "-", ",", "." and space. The
     * field asks for the number-password input type (a digits-only pad), and still shows and reports its digits.
     */
    @Test
    fun theIndexFieldAsksForADigitsOnlyKeyboard() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Column { AddressIndexControl(7, {}, AccountProfiles.TRON_PATH_PREFIX) }
            }
        }
        val field = compose.onNodeWithTag("connect.tronIndex.field")
        field.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val editorInfo = EditorInfo()
        compose.runOnIdle { assertNotNull(view.onCreateInputConnection(editorInfo), "an input session is open") }
        assertEquals(InputType.TYPE_CLASS_NUMBER, editorInfo.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_NUMBER_VARIATION_PASSWORD, editorInfo.inputType and InputType.TYPE_MASK_VARIATION)
        field.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher("shows its digits") { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "7" })
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

    /**
     * Backlog B5: in a right-to-left interface the detail line "BIP84 · bc1q…" is still laid out left to right, so its
     * ellipsis stays after "bc1q" (it used to move to the front: "…BIP84 · bc1q"). In the row and in the menu.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theDetailKeepsItsEllipsisAtTheEndRightToLeft() {
        val native = AccountProfiles.bitcoinSingle.first()
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column {
                        ProfileMenu(
                            title = "Address type",
                            options = AccountProfiles.bitcoinSingle,
                            selection = native,
                            label = AccountProfile::title,
                            detail = AccountProfile::detail,
                            onSelect = {},
                        )
                    }
                }
            }
        }
        fun assertEllipsisLast(node: SemanticsNode) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            val text = layout.layoutInput.text.text
            assertEquals("BIP84 · bc1q…", text)
            assertEquals(TextDirection.Content, layout.layoutInput.style.textDirection, "laid out in its own direction")
            assertEquals(ResolvedTextDirection.Ltr, layout.getParagraphDirection(0))
            val ellipsis = layout.getBoundingBox(text.lastIndex)
            val first = layout.getBoundingBox(0)
            assertTrue(ellipsis.left > first.right, "the ellipsis is drawn after the text, not before it")
        }
        assertEllipsisLast(compose.onNodeWithText(native.detail, useUnmergedTree = true).fetchSemanticsNode())
        compose.onNodeWithTag("profile.menu").performClick()
        val inMenu = compose.onAllNodesWithText(native.detail, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, inMenu.size, "the row and the menu item")
        inMenu.forEach(::assertEllipsisLast)
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
