package com.bittool.signer.core.scanner

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.bittool.signer.core.model.QrMatrix

/**
 * One pixel per module, exactly like iOS `QRRenderer` (CoreImage `qrCodeGenerator` through a CPU context,
 * `Design/QRCodeView.swift:6-19`): black modules on white with CoreImage's **one-module quiet zone**, so a version-1
 * symbol is 23 × 23 px (design-system.md §5.12). The view scales the image with nearest-neighbour sampling.
 */
internal object QrRendering {
    /** CoreImage's built-in quiet zone, in modules, on every side. */
    const val QUIET_ZONE_MODULES = 1

    private const val DARK = 0xFF000000.toInt()
    private const val LIGHT = 0xFFFFFFFF.toInt()

    /** Side of the rendered image in pixels (= modules plus the quiet zone on both sides). */
    fun sideOf(matrix: QrMatrix): Int = matrix.size + 2 * QUIET_ZONE_MODULES

    /** ARGB pixels, row-major, `sideOf(matrix)²` long. Pure, so it is unit-tested on the JVM. */
    fun pixels(matrix: QrMatrix): IntArray {
        val side = sideOf(matrix)
        val pixels = IntArray(side * side) { LIGHT }
        for (y in 0 until matrix.size) {
            val row = (y + QUIET_ZONE_MODULES) * side + QUIET_ZONE_MODULES
            for (x in 0 until matrix.size) if (matrix[x, y]) pixels[row + x] = DARK
        }
        return pixels
    }

    fun bitmap(matrix: QrMatrix): Bitmap {
        val side = sideOf(matrix)
        return Bitmap.createBitmap(pixels(matrix), side, side, Bitmap.Config.ARGB_8888)
    }

    fun imageBitmap(matrix: QrMatrix): ImageBitmap = bitmap(matrix).asImageBitmap()
}
