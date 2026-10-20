package com.bittool.signer.core.designsystem.theme

import androidx.compose.animation.core.SpringSpec
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The SwiftUI → Compose spring conversion of design-system.md §6.1. */
class SignerMotionTest {

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 1f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected ≈$expected, was $actual")

    @Test
    fun stiffnessIsTwoPiOverDurationSquared() {
        // The values tabulated in design-system §6.1.
        assertNear(322f, SignerMotion.stiffnessFor(0.35))
        assertNear(439f, SignerMotion.stiffnessFor(0.3))
        assertNear(632f, SignerMotion.stiffnessFor(0.25))
        assertNear(987f, SignerMotion.stiffnessFor(0.2))
        assertNear(504f, SignerMotion.stiffnessFor(0.28))
        assertNear(1218f, SignerMotion.stiffnessFor(0.18))
        assertNear(1755f, SignerMotion.stiffnessFor(0.15))
        assertNear(62f, SignerMotion.stiffnessFor(0.8))
    }

    @Test
    fun smoothNeverBouncesAndSnappyBouncesALittleUnlessMotionIsReduced() {
        val smooth = SignerMotion.smooth<Float>(0.35)
        assertEquals(1f, smooth.dampingRatio)
        val snappy: SpringSpec<Float> = SignerMotion.snappy(0.25)
        assertEquals(0.85f, snappy.dampingRatio)
        assertEquals(1f, SignerMotion.snappy<Float>(0.25, reduceMotion = true).dampingRatio)
        assertEquals(1f, SignerMotion.chainPicker<Float>(reduceMotion = true).dampingRatio)
        assertNear(504f, SignerMotion.chainPicker<Float>().stiffness)
    }

    @Test
    fun timingsMatchIos() {
        assertEquals(1_600L, SignerMotion.TOAST_MILLIS)
        assertEquals(1_500L, SignerMotion.COPIED_MILLIS)
    }
}
