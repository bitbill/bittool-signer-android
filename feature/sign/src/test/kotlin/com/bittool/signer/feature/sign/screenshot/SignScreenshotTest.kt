package com.bittool.signer.feature.sign.screenshot

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.bittool.signer.feature.sign.InLook
import com.bittool.signer.feature.sign.Look
import com.bittool.signer.feature.sign.SignPages
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Records every Scan tab page (Home, paste, review states, authorize, signature) in light, dark, font scale 2.0 and
 * Arabic RTL to `feature/sign/build/outputs/roborazzi/sign/` for visual QA (record only; `-Psigner.screenshots=false`
 * skips it). A tall phone window so the review's sections and its pinned bar are in one image.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h1800dp-xhdpi")
class SignScreenshotTest(private val name: String, private val look: Look) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun page() {
        if (look.rtl) RuntimeEnvironment.setQualifiers("+ar")
        compose.setContent { InLook(look) { SignPages.getValue(name)() } }
        if (name.startsWith("30") || name.startsWith("31")) {
            // The response QR is encoded off the main thread before it is drawn.
            compose.waitUntil(10_000) {
                compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/sign/$name-${look.name.lowercase()}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> = SignPages.keys.flatMap { page -> Look.entries.map { arrayOf<Any>(page, it) } }
    }
}
