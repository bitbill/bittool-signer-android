package com.bittool.signer.core.scanner.scan

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.unit.Density
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The viewfinder of iOS `ViewfinderOverlay` / `Brackets` (`QRScannerView.swift:146-203`; scan-sign.md §6.5): window
 * size and position, the progress ring's inset and starting point, the bracket arms.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real android.graphics.PathMeasure
class ViewfinderGeometryTest {
    private val density = Density(3f)

    private fun geometry(widthDp: Float, heightDp: Float) = ViewfinderGeometry.of(Size(widthDp * 3, heightDp * 3), density)

    private fun assertRect(expectedDp: Rect, actualPx: Rect) {
        val expected = Rect(expectedDp.left * 3, expectedDp.top * 3, expectedDp.right * 3, expectedDp.bottom * 3)
        for ((name, pair) in listOf(
            "left" to (expected.left to actualPx.left),
            "top" to (expected.top to actualPx.top),
            "right" to (expected.right to actualPx.right),
            "bottom" to (expected.bottom to actualPx.bottom),
        )) {
            assertEquals(pair.first, pair.second, 0.01f, "$name of $actualPx")
        }
    }

    @Test
    fun aPortraitPhoneGetsAWindowOfItsWidthLessTheMarginsLiftedTwenty() {
        // side = min(390 − 64, 844 × 0.5, 340) = 326, centred, then 20 above the middle.
        val g = geometry(390f, 844f)
        assertRect(Rect(32f, 239f, 358f, 565f), g.window)
        assertEquals(28f * 3, g.windowRadius)
        assertEquals(26f * 3, g.bracketRadius)
        // The ring is the window less 12 (6 on each side), with the same radius 28.
        assertRect(Rect(38f, 245f, 352f, 559f), g.ring)
    }

    @Test
    fun theWindowNeverExceeds340AndInLandscapeHalfTheHeight() {
        assertRect(Rect(342f, 222f, 682f, 562f), geometry(1024f, 824f).window) // tablet: capped at 340
        assertRect(Rect(324.5f, 77.5f, 519.5f, 272.5f), geometry(844f, 390f).window) // landscape: 390 × 0.5
    }

    /**
     * QA sign-09: the status panel (camera access needed at font scale 2) used to cover the lower brackets. With the
     * free band between the top bar and the panel, the window keeps 12 dp from both: it moves up first, then shrinks
     * to the band, never below 120 dp. A window that fits is the iOS one.
     */
    @Test
    fun theWindowKeepsClearOfTheTopBarAndTheStatusPanel() {
        val size = Size(393f * 3, 807f * 3)
        fun clear(top: Float, bottom: Float) = ViewfinderGeometry.of(size, density, (top * 3)..(bottom * 3)).window
        // iOS: side min(393 − 64, 403.5, 340) = 329 at 219…548.
        assertRect(Rect(32f, 219f, 361f, 548f), geometry(393f, 807f).window)
        assertRect(Rect(32f, 219f, 361f, 548f), clear(84f, 700f))
        // A panel from 523: moved up to end 12 above it, same size.
        assertRect(Rect(32f, 182f, 361f, 511f), clear(84f, 523f))
        // A panel from 400: the band 96…388 is smaller than the window, which shrinks to it.
        assertRect(Rect(50.5f, 96f, 342.5f, 388f), clear(84f, 400f))
        // Hardly any room: never below 120, at the band's top.
        assertRect(Rect(136.5f, 96f, 256.5f, 216f), clear(84f, 200f))
    }

    @Test
    fun theProgressRingStartsAtTheTopLeftCornerAndRunsClockwise() {
        val ring = Rect(0f, 0f, 300f, 300f)
        val r = 84f
        val measure = PathMeasure().apply { setPath(ringPath(ring, r), false) }
        // Perimeter of a rounded square: four straight sides plus one full circle (Skia measures the arcs through
        // their curve approximation, within 0.2 %).
        val perimeter = 4 * (300f - 2 * r) + 2 * PI.toFloat() * r
        assertEquals(perimeter, measure.length, perimeter * 0.002f)
        assertClose(Offset(r, 0f), measure.getPosition(0f))
        // Clockwise: a quarter of the way is the end of the top-right corner, halfway the start of the bottom edge.
        assertClose(Offset(300f, r), measure.getPosition(measure.length * 0.25f))
        assertClose(Offset(300f - r, 300f), measure.getPosition(measure.length * 0.5f))
    }

    @Test
    fun bracketArmsAreSixteenPercentOfTheSideWithRoundedCorners() {
        val window = Rect(0f, 0f, 1000f, 1000f)
        val r = 78f
        val measure = PathMeasure().apply { setPath(bracketsPath(window, r), false) }
        // The first contour is the top-left bracket: arm 160 down the left edge to the curve, the curve, then along
        // the top edge to 160. A quadratic curve with its control point at the corner is shorter than the two legs.
        assertClose(Offset(0f, 160f), measure.getPosition(0f))
        val straight = 2 * (160f - r)
        assertTrue(measure.length > straight + r * 1.4f && measure.length < straight + 2 * r, "length ${measure.length}")
        assertClose(Offset(160f, 0f), measure.getPosition(measure.length))
    }

    private fun assertClose(expected: Offset, actual: Offset) {
        assertTrue(abs(expected.x - actual.x) < 0.5f && abs(expected.y - actual.y) < 0.5f, "expected $expected, was $actual")
    }
}
