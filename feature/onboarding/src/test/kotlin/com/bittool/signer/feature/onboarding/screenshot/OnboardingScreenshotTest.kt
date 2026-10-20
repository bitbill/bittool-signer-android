package com.bittool.signer.feature.onboarding.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.feature.onboarding.OnboardingPager
import com.bittool.signer.feature.onboarding.OnboardingTags
import com.bittool.signer.feature.onboarding.WelcomeContent
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The looks every screen is recorded in. */
enum class Look(val dark: Boolean, val fontScale: Float = 1f, val rtl: Boolean = false) {
    Light(dark = false),
    Dark(dark = true),
    FontScale2(dark = false, fontScale = 2f),
    Rtl(dark = true, rtl = true),
}

/**
 * Records Welcome and the three introduction pages to `feature/onboarding/build/outputs/roborazzi/` for visual QA
 * (record only, no golden comparison; `-Psigner.screenshots=false` skips them). A phone-sized window.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingScreenshotTest(private val look: Look) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun welcome() {
        render { WelcomeContent(onStart = {}, onShowLegal = {}) }
        capture("welcome")
    }

    @Test
    fun introduction() {
        render { OnboardingPager(intent = SetupIntent.Create, onComplete = {}, onCancel = {}) }
        capture("onboarding-1")
        repeat(2) { index ->
            compose.onNodeWithTag(OnboardingTags.NEXT).performClick()
            compose.mainClock.advanceTimeBy(1_500)
            capture("onboarding-${index + 2}")
        }
    }

    private fun render(content: @Composable () -> Unit) {
        if (look.rtl) RuntimeEnvironment.setQualifiers("+ar")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            SignerTheme(darkTheme = look.dark, applySystemBarAppearance = false) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, look.fontScale),
                    LocalLayoutDirection provides if (look.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/onboarding/$name-${look.name.lowercase()}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = Look.entries.map { arrayOf<Any>(it) }
    }
}
