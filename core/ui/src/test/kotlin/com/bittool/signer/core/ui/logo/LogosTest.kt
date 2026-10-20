package com.bittool.signer.core.ui.logo

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.NetworkBrand
import com.bittool.signer.core.model.TokenIconSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Network, chain and token logos (iOS `Design/Logos.swift:83-185`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogosTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun clearCache() = TokenIconCache.clear()

    @Test
    fun logosAreLabelledWithTheirVerbatimNames() {
        compose.setContent {
            SignerTheme(false, false) {
                Row {
                    NetworkLogo(NetworkBrand.Bnb)
                    ChainGlyph(Chain.Evm)
                    ChainLogo(Chain.Solana)
                    NetworkLogoStack()
                }
            }
        }
        compose.onNodeWithContentDescription("BNB Smart Chain").assertExists()
        compose.onNodeWithContentDescription("EVM networks").assertExists()
        compose.onNodeWithContentDescription("Solana").assertExists()
        compose.onNodeWithContentDescription("Bitcoin, Ethereum, BNB Chain, Arbitrum, Base, TRON, Solana").assertExists()
    }

    @Test
    fun aTokenWithoutArtworkShowsItsLetters() {
        compose.setContent { SignerTheme(false, false) { TokenIcon(iconId = null, symbol = "usdt") } }
        compose.onNodeWithContentDescription("usdt").assertExists()
        compose.onNodeWithText("USD", useUnmergedTree = true).assertExists()
    }

    @Test
    fun bundledArtworkIsDecodedOnceAndShared() {
        val png = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
                .compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val reads = AtomicInteger()
        val source = TokenIconSource { id -> reads.incrementAndGet(); if (id == "0123456789abcdef") png else null }
        compose.setContent {
            SignerTheme(false, false) {
                CompositionLocalProvider(LocalTokenIconSource provides source) {
                    Row {
                        TokenIcon(iconId = "0123456789abcdef", symbol = "USDT")
                        TokenIcon(iconId = "0123456789abcdef", symbol = "USDT")
                        TokenIcon(iconId = "ffffffffffffffff", symbol = "DAI")
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { TokenIconCache.peek("0123456789abcdef") != null }
        compose.waitForIdle()
        // The artwork replaced the letters for USDT; DAI has no artwork and keeps them.
        compose.onNodeWithText("USD", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("DAI", useUnmergedTree = true).assertExists()
        assertEquals(2, compose.onAllNodesWithContentDescription("USDT").fetchSemanticsNodes().size)
        compose.onAllNodesWithContentDescription("DAI").fetchSemanticsNodes().single()
        // A missing icon is not cached (a later attempt may succeed); a found one is read at most once per row.
        assertEquals(true, reads.get() <= 3)
        compose.onNodeWithContentDescription("DAI").assertContentDescriptionEquals("DAI")
    }
}
