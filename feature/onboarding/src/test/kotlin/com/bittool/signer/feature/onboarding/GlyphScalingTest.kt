package com.bittool.signer.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.navigation.SetupIntent
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA regression-07: the Welcome promise symbols and the introduction's key-point symbols follow the text size, like
 * iOS SF Symbols with Dynamic Type (`WelcomeView.swift:98-99` `.font(.title2)`, the points' `Label` icons). At font
 * scale 2.0 they stayed 26 dp (Welcome) and were capped at 24 dp (a fixed-width column) beside text at double size.
 * The default size is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h900dp")
class GlyphScalingTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SignerTheme(darkTheme = false, applySystemBarAppearance = false, content = content)
            }
        }
        compose.waitForIdle()
    }

    private fun widths(tag: String): List<Dp> = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .map { with(compose.density) { it.size.width.toDp() } }

    private fun assertAbout(expected: Dp, actual: Dp, what: String) =
        assertTrue(kotlin.math.abs((expected - actual).value) < 0.6f, "$what is $actual, expected $expected")

    @Test
    fun welcomeSymbolsAre26DpAtTheDefaultSize() {
        show(1f) { WelcomeContent(onStart = {}, onShowLegal = {}) }
        val sizes = widths(WelcomeTags.FEATURE_ICON)
        assertEquals(3, sizes.size, "three promises")
        sizes.forEach { assertAbout(26.dp, it, "a promise symbol") }
    }

    /** The size of [sp] text at font scale 2, with the platform's (non-linear, Android 14+) font scaling. */
    private fun atDoubleSize(sp: TextUnit): Dp = with(Density(compose.density.density, 2f)) { sp.toDp() }

    @Test
    fun welcomeSymbolsGrowWithLargeText() {
        show(2f) { WelcomeContent(onStart = {}, onShowLegal = {}) }
        val sizes = widths(WelcomeTags.FEATURE_ICON)
        assertEquals(3, sizes.size)
        // 26 dp beside 22 sp title2 text: the same share of title2 at double size.
        val expected = atDoubleSize(22.sp * (26f / 22f))
        assertTrue(expected > 26.dp * 1.3f, "precondition: font scale 2 enlarges title2 ($expected)")
        sizes.forEach { assertAbout(expected, it, "a promise symbol at font scale 2") }
    }

    @Test
    fun introductionPointSymbolsKeepTheirColumnAtTheDefaultSize() {
        show(1f) { OnboardingPager(intent = SetupIntent.Create, onComplete = {}, onCancel = {}) }
        val columns = widths(OnboardingTags.POINT_ICON)
        assertTrue(columns.isNotEmpty())
        columns.forEach { assertAbout(24.dp, it, "a key-point symbol column") }
    }

    @Test
    fun introductionPointSymbolsAreNotCappedWithLargeText() {
        show(2f) { OnboardingPager(intent = SetupIntent.Create, onComplete = {}, onCancel = {}) }
        val columns = widths(OnboardingTags.POINT_ICON)
        assertTrue(columns.isNotEmpty())
        // The symbol is 1.15 × the 15 sp subheadline, no longer squeezed into 24 dp.
        val expected = atDoubleSize(15.sp * 1.15f)
        assertTrue(expected > 24.dp, "precondition: the symbol outgrows the 24 dp column ($expected)")
        columns.forEach { assertAbout(expected, it, "a key-point symbol column at font scale 2") }
    }
}
