package com.bittool.signer.feature.sign.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.feature.sign.ReviewStates
import com.bittool.signer.feature.sign.common.SignTags
import com.bittool.signer.feature.sign.review.RequestReviewContent
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * QA sign-04 for visual QA: a request that needs the acknowledgement, at font scale 2.0 on the QA phone's page (393 ×
 * 807 dp less the ~80 dp tab bar), where [SignScreenshotTest]'s tall window never moves the acknowledgement out of the
 * bar. Records the top of the page (only Sign pinned) and its end (the acknowledgement as the last item, directly
 * above the bar) in light, dark and Arabic right to left to `feature/sign/build/outputs/roborazzi/sign/` (record only).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h727dp-xxhdpi")
class ReviewLargeTextScreenshotTest(private val name: String, private val dark: Boolean, private val rtl: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun partiallyDecodedReviewAtFontScale2() {
        if (rtl) RuntimeEnvironment.setQualifiers("+ar")
        compose.setContent {
            val density = LocalDensity.current
            SignerTheme(darkTheme = dark, applySystemBarAppearance = false) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, 2f),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Box(Modifier.fillMaxSize().background(SignerTheme.colors.page)) {
                        RequestReviewContent(ReviewStates.partial, onReject = {}, onToggleRisk = {}, onRetry = {}, onAuthorize = {})
                    }
                }
            }
        }
        val file = "build/outputs/roborazzi/sign/19-review-phone-fontscale2-$name"
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("$file-top.png")
        val list = compose.onNodeWithTag(SignTags.REVIEW_LIST).performScrollToNode(hasTestTag(SignTags.RISK))
        repeat(3) { list.performTouchInput { swipeUp() } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("$file-end.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = listOf(
            arrayOf("light", false, false),
            arrayOf("dark", true, false),
            arrayOf("rtl", true, true),
        )
    }
}
