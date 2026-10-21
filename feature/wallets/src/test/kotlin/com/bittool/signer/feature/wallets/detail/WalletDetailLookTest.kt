package com.bittool.signer.feature.wallets.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.theme.SignerColors
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.feature.wallets.WalletFixtures
import com.bittool.signer.feature.wallets.common.WalletIdentity
import kotlin.math.abs
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * QA regression-14 (round 1 wallets-14), measured in pixels with native graphics: on iOS the wallet page's Security
 * rows, "Rename Wallet" and "Repair Wallet Data" are list buttons (`WalletDetailView.swift:43-45, 75-93`), drawn in the
 * accent tint with no chevron (`build/ios-reference/light-12-wallet-detail-bottom.png`). Rows that push a page keep
 * their label colour and chevron.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h1600dp-xhdpi")
class WalletDetailLookTest {
    @get:Rule
    val compose = createComposeRule()

    private val colors = SignerColors.Light

    private fun showPage(walletId: WalletId, repair: Boolean = false) {
        val state = WalletFixtures.state(repair = repair)
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                WalletDetailContent(
                    uiState = WalletDetailUiState(walletId = walletId, identity = WalletIdentity.of(state, walletId)),
                    actions = WalletDetailActions(),
                    toast = rememberStatusToastState(),
                    onCopyFingerprint = {},
                    onRename = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun Color.isNear(other: Color): Boolean =
        abs(red - other.red) < 0.04f && abs(green - other.green) < 0.04f && abs(blue - other.blue) < 0.04f

    private fun PixelMap.any(xs: IntRange = 0 until width, ys: IntRange = 0 until height, predicate: (Color) -> Boolean): Boolean =
        xs.any { x -> ys.any { y -> predicate(this[x, y]) } }

    /** The title's pixels: some in the accent, none near the label colour (black). */
    private fun assertAccentTitle(title: String) {
        val pixels = compose.onNode(hasText(title), useUnmergedTree = true).captureToImage().toPixelMap()
        assertTrue(pixels.any { it.isNear(colors.accent) }, "“$title” is drawn in the accent")
        assertFalse(pixels.any { it.red < 0.25f && it.green < 0.25f && it.blue < 0.25f }, "“$title” is not label-coloured")
    }

    /**
     * Anything but the card colour in the row's trailing 44 dp, where `NavRow` draws its chevron (20 dp, 16 dp in),
     * across the middle of the row: the chevron is centred, and the rounded corners of a section's first and last rows
     * (22 dp) stay out of the scan.
     */
    private fun hasChevron(tag: String): Boolean {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        val trailing = with(compose.density) { 44.dp.roundToPx() }
        val middle = (pixels.height * 0.42f).toInt()..(pixels.height * 0.58f).toInt()
        return pixels.any(xs = pixels.width - trailing until pixels.width, ys = middle) { it != colors.card }
    }

    @Test
    fun theSecurityAndRenameRowsAreAccentButtonsWithoutChevrons() {
        showPage(WalletFixtures.readyId)
        mapOf(
            "detail.recoveryPhrase" to "Show Recovery Phrase",
            "detail.changePassword" to "Change Password",
            "detail.rename" to "Rename Wallet",
        ).forEach { (tag, title) ->
            assertAccentTitle(title)
            assertFalse(hasChevron(tag), "$tag opens a page or dialog over the wallet: no chevron")
        }
        // A row that pushes a page keeps the NavigationLink look (and shows the chevron check sees one).
        assertTrue(hasChevron("detail.addresses"), "Receive Addresses keeps its chevron")
        assertTrue(hasChevron("detail.connect.bitcoin"), "Pair rows keep their chevron")
    }

    @Test
    fun theRepairRowIsAnAccentButtonWithoutChevron() {
        showPage(WalletFixtures.repairId, repair = true)
        assertAccentTitle("Repair Wallet Data")
        assertFalse(hasChevron("detail.repair"))
    }
}
