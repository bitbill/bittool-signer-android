package com.bittool.signer.core.scanner

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * iOS draws one pixel per module scaled with `.interpolation(.none)` (`Design/QRCodeView.swift:60-63`): every module
 * must come out as the same whole number of pure black or white pixels at any density, or a camera reads blurred,
 * uneven modules.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrCodeViewPixelsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun modulesAreWholePixelSquaresWithoutSmoothingAtAFractionalDensity() {
        val text = "UR:BYTES/PIXELS"
        val matrix = ZxingQrCodeEncoder().encode(text)
        compose.setContent {
            // 2.625 (420 dpi): the plate's inner square is not a multiple of the 23-pixel image.
            CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
                QrCodeView(frames = listOf(text), initial = listOf(matrix), maxSide = 200.dp)
            }
        }
        val pixels = compose.onNodeWithTag(QrCodeViewTags.PLATE).captureToImage().toPixelMap()
        val middle = pixels.height / 2
        val row = (0 until pixels.width).map { pixels[it, middle] }
        val dark = row.indexOfFirst { it == Color.Black }
        val lastDark = row.indexOfLast { it == Color.Black }
        assertTrue(dark > 0 && lastDark > dark, "the middle row crosses the code")
        val code = row.subList(dark, lastDark + 1)
        assertTrue(code.all { it == Color.Black || it == Color.White }, "only pure black and white inside the code")
        // Runs of equal pixels across the code are whole multiples of one module size.
        val runs = ArrayList<Int>()
        var length = 1
        for (i in 1 until code.size) {
            if (code[i] == code[i - 1]) length++ else { runs += length; length = 1 }
        }
        runs += length
        val cell = runs.min()
        assertTrue(cell >= 2, "module size $cell px")
        assertTrue(runs.all { it % cell == 0 }, "runs $runs are not multiples of $cell px")
        // The symbol row spans whole modules: 21 modules for this version-1 code, minus light modules at its ends.
        assertEquals(0, code.size % cell)
        assertTrue(code.size / cell <= matrix.size)
    }
}
