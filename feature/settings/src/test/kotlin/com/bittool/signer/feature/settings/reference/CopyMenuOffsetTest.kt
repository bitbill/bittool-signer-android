package com.bittool.signer.feature.settings.reference

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import org.junit.Test

/** QA sign-15: the "Copy Contract Address" menu opens at the finger in both directions. */
class CopyMenuOffsetTest {
    private val density = Density(2f)
    private val row = IntSize(800, 128)

    @Test
    fun theMenuStartsWhereTheRowWasPressed() {
        // DropdownMenu measures x from the anchor's start edge and y from its bottom: the top of the menu at the finger.
        assertEquals(DpOffset(120.dp, (-34).dp), menuOffsetAt(Offset(240f, 60f), row, LayoutDirection.Ltr, density))
    }

    @Test
    fun rightToLeftTheMenuStartsAtTheFingerFromTheRightEdge() {
        assertEquals(DpOffset(280.dp, (-34).dp), menuOffsetAt(Offset(240f, 60f), row, LayoutDirection.Rtl, density))
    }
}
