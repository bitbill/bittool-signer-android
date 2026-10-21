package com.bittool.signer.feature.wallets.addresses

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.bittool.signer.core.designsystem.theme.SignerColors
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.Chain
import com.bittool.signer.feature.wallets.WalletFixtures
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * How the Addresses page and its sheet look, measured with real text and pixels (native graphics): the sheet's card
 * colour (QA wallets-02) and the Solana label column (QA wallets-13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h900dp-xhdpi")
class AddressesLookTest {
    @get:Rule
    val compose = createComposeRule()

    private fun cardColour(dark: Boolean): Color {
        val account = WalletFixtures.accounts(Chain.Bitcoin, 0, 1).first()
        // A manual clock: the QR code's spinner and the sheet's animation never wait for the off-main encoding.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SignerTheme(darkTheme = dark, applySystemBarAppearance = false) {
                AddressDetailSheet(account = account, title = "Bitcoin address #0", onDismiss = {})
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        val pixels = compose.onNodeWithTag("address.card").captureToImage().toPixelMap()
        // Inside the card padding, clear of the rounded corners and of the text.
        return pixels[4, pixels.height / 2]
    }

    /**
     * iOS `AddressesView.swift:187` `.cardBackground()`: in dark mode the card is the elevated card colour on the sheet,
     * not the page palette's card, which is the sheet's own colour (an invisible card).
     */
    @Test
    fun inDarkModeTheSheetCardIsTheElevatedCardColour() {
        val card = cardColour(dark = true)
        assertEquals(SignerColors.DarkElevated.card, card, "#2C2C2E on the #1C1C1E sheet")
        assertNotEquals(SignerColors.DarkElevated.page, card, "the card stands out from the sheet")
    }

    @Test
    fun inLightModeTheSheetCardIsWhite() {
        assertEquals(SignerColors.Light.card, cardColour(dark = false))
    }

    /** Solana's rows (Standard, Trust, Legacy) share one label column, so their addresses line up. */
    @Test
    fun solanaAddressesStartAtOneX() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                AddressesContent(AddressesUiState(chain = Chain.Solana, accounts = WalletFixtures.solanaAccounts, loaded = true), AddressesActions())
            }
        }
        val starts = WalletFixtures.solanaAccounts.map { account ->
            compose.onNodeWithContentDescription(account.address.take(4), substring = true, useUnmergedTree = true).getBoundsInRoot().left
        }
        assertEquals(1, starts.toSet().size, "address columns start at $starts")
    }
}
