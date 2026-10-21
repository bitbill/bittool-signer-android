package com.bittool.signer.feature.onboarding

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Welcome on a compact phone (QA setup-13): on the QA phone (393 × 807 dp) the network logos and names were cut by the
 * bar on the very first screen of the app, about 70 dp short. Robolectric draws no status bar (24 dp) and no navigation
 * bar (48 dp), so the same viewport is a 735 dp window here; native graphics lay the text out with the device's fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeCompactLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                WelcomeContent(onStart = {}, onShowLegal = {})
            }
        }
        compose.waitForIdle()
    }

    /** Unclipped bounds (`boundsInRoot` is clipped to the scrolling viewport: empty for a node scrolled out of it). */
    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }

    private fun bounds(text: String): Rect = compose.onNodeWithText(text).bounds()

    private fun barTop(): Float = compose.onNodeWithTag(WelcomeTags.CREATE).bounds().top - with(compose.density) { 8.dp.toPx() }

    @Test
    @Config(qualifiers = "w393dp-h735dp-xxhdpi")
    fun theWholePageFitsAboveTheBarOnACompactPhone() {
        show()
        val barTop = barTop()
        val names = bounds("Bitcoin, Ethereum, BNB Chain, Arbitrum, Base, TRON and Solana")
        assertTrue(names.bottom <= barTop, "the network names end at ${names.bottom}, under the bar at $barTop")
        val content = compose.onNodeWithTag(WelcomeTags.CONTENT).bounds()
        assertTrue(content.bottom <= barTop + 1f, "the page (${content.bottom}) must not continue under the bar ($barTop)")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun nothingShrinksWhenThePageFits() {
        show()
        // 32 dp above the 96 dp tile and 14 dp below it, at least (the free height is shared between the gaps).
        val titleTop = bounds("BitTool Signer").top
        assertTrue(titleTop >= with(compose.density) { (32 + 96 + 14).dp.toPx() } - 1f, "title at $titleTop: the tile or its gaps shrank")
        assertTrue(bounds("Bitcoin, Ethereum, BNB Chain, Arbitrum, Base, TRON and Solana").bottom <= barTop())
    }
}
