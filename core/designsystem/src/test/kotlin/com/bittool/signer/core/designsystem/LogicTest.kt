package com.bittool.signer.core.designsystem

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.geometry.Size
import com.bittool.signer.core.designsystem.brand.SuperellipseShape
import com.bittool.signer.core.designsystem.component.segmentWidths
import com.bittool.signer.core.designsystem.haptics.HapticKind
import com.bittool.signer.core.designsystem.haptics.hapticConstantFor
import com.bittool.signer.core.designsystem.security.applyNoPersonalizedLearning
import com.bittool.signer.core.designsystem.security.isObscuredTouch
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pure rules of the design system that need Android types. */
@RunWith(RobolectricTestRunner::class)
class LogicTest {

    @Test
    fun hapticsUseTheClosestConstantPerApiLevel() {
        assertEquals(HapticFeedbackConstants.CONFIRM, hapticConstantFor(HapticKind.Success, Build.VERSION_CODES.R))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, hapticConstantFor(HapticKind.Success, Build.VERSION_CODES.Q))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, hapticConstantFor(HapticKind.Selection, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticConstantFor(HapticKind.Selection, Build.VERSION_CODES.TIRAMISU))
        assertEquals(HapticFeedbackConstants.REJECT, hapticConstantFor(HapticKind.Reject, Build.VERSION_CODES.R))
        assertEquals(HapticFeedbackConstants.REJECT, hapticConstantFor(HapticKind.Warning, Build.VERSION_CODES.BAKLAVA))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticConstantFor(HapticKind.Warning, Build.VERSION_CODES.O))
    }

    @Test
    fun obscuredTouchesAreRecognisedByTheirFlags() {
        assertTrue(isObscuredTouch(MotionEvent.FLAG_WINDOW_IS_OBSCURED, Build.VERSION_CODES.O))
        assertTrue(isObscuredTouch(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED, Build.VERSION_CODES.Q))
        // The partial flag only exists from API 29; below it the bit means nothing.
        assertFalse(isObscuredTouch(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED, Build.VERSION_CODES.P))
        assertFalse(isObscuredTouch(0, Build.VERSION_CODES.BAKLAVA))
    }

    @Test
    fun noPersonalizedLearningAddsTheImeFlagAndKeepsTheOthers() {
        val info = EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_DONE }
        applyNoPersonalizedLearning(info)
        assertEquals(EditorInfo.IME_ACTION_DONE, info.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    @Test
    fun segmentsShareTheWidthEquallyUntilALabelDoesNotFit() {
        assertEquals(listOf(150, 150), segmentWidths(listOf(80, 120), 300))
        // A long translation: label widths plus an equal share of the rest.
        assertEquals(listOf(70, 230), segmentWidths(listOf(40, 200), 300))
        // Too long for the row: equal shares, the labels wrap.
        assertEquals(listOf(150, 150), segmentWidths(listOf(260, 200), 300))
        // The widths always fill the track exactly, remainders go to the first segments.
        assertEquals(301, segmentWidths(listOf(10, 10, 10), 301).sum())
        assertEquals(listOf(101, 100, 100), segmentWidths(listOf(10, 10, 10), 301))
        assertEquals(emptyList(), segmentWidths(emptyList(), 100))
    }

    @Test
    fun superellipseFillsItsBoundsWithContinuousCorners() {
        val shape = SuperellipseShape(5f)
        val bounds = shape.path(Size(100f, 100f)).getBounds()
        assertNear(0f, bounds.left)
        assertNear(0f, bounds.top)
        assertNear(100f, bounds.right)
        assertNear(100f, bounds.bottom)
        // At 45° the n = 5 curve reaches (0.5 + 0.5 · cos(45°)^0.4) of the side: far into the corner, unlike a circle.
        val reach = 50f + 50f * Math.pow(Math.cos(Math.PI / 4), 0.4).toFloat()
        assertTrue(reach in 93f..94f, "corner reach $reach")
        assertTrue(SuperellipseShape(5f) == SuperellipseShape(5f))
    }

    private fun assertNear(expected: Float, actual: Float) = assertTrue(abs(expected - actual) < 0.5f, "expected $expected, was $actual")
}
