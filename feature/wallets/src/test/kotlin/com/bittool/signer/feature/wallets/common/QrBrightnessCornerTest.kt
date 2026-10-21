package com.bittool.signer.feature.wallets.common

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.scanner.QrCodeView
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Normal / Bright" capsule on a pairing QR code (QA wallets-06): centred on the plate's top edge and inside the
 * plate's width, clear of its rounded end corner, so it never sticks out into the page gutter (Wallet Level's plate is
 * as wide as the page), and its lower half stays inside the 18 dp quiet zone, so it never covers a module.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h900dp-mdpi")
class QrBrightnessCornerTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(direction: LayoutDirection) = compose.setContent {
        SignerTheme(darkTheme = true, applySystemBarAppearance = false) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                // Wallet Level: a 360 dp plate filling the page between its 20 dp margins.
                QrBrightnessCorner(maxSide = 360.dp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 40.dp)) { bright ->
                    QrCodeView(frames = listOf("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"), maxSide = 360.dp, maxBrightness = bright)
                }
            }
        }
    }

    private fun assertOnThePlateEdge(direction: LayoutDirection) {
        show(direction)
        val plate = compose.onNodeWithTag("qr.plate", useUnmergedTree = true).getBoundsInRoot()
        val toggle = compose.onNodeWithTag(QR_BRIGHTNESS_TAG).getBoundsInRoot()
        assertTrue(toggle.left >= plate.left && toggle.right <= plate.right, "inside the plate's width: $toggle on $plate")
        if (direction == LayoutDirection.Ltr) {
            assertEquals(plate.right - QR_PLATE_CORNER_RADIUS, toggle.right, "clear of the top-end corner")
        } else {
            assertEquals(plate.left + QR_PLATE_CORNER_RADIUS, toggle.left, "clear of the top-end corner (mirrored)")
        }
        // The 26 dp capsule is centred in its 48 dp target, so centred on the plate's top edge: 13 dp of it lie on the
        // plate, inside the 18 dp quiet zone.
        assertEquals(plate.top, (toggle.top + toggle.bottom) / 2, "centred on the top edge")
    }

    @Test
    fun theCapsuleSitsOnThePlatesTopEdgeInsideItsEndCorner() = assertOnThePlateEdge(LayoutDirection.Ltr)

    @Test
    fun inRightToLeftTheCapsuleSitsInsideTheMirroredCorner() = assertOnThePlateEdge(LayoutDirection.Rtl)
}
