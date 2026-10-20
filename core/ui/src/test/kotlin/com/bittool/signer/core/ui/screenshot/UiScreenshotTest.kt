package com.bittool.signer.core.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.ui.catalog.CatalogSamples
import com.bittool.signer.core.ui.catalog.UiCatalogSections
import com.bittool.signer.core.ui.review.ReviewSummaryCard
import com.bittool.signer.core.ui.text.rememberReviewText
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The four looks every component is recorded in (design-system.md §9.14). */
enum class Look(val dark: Boolean, val fontScale: Float, val rtl: Boolean) {
    Light(false, 1f, false),
    Dark(true, 1f, false),
    FontScale2(false, 2f, false),
    Rtl(true, 1f, true),
}

@Composable
internal fun InLook(look: Look, width: androidx.compose.ui.unit.Dp? = null, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    SignerTheme(darkTheme = look.dark, applySystemBarAppearance = false) {
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, look.fontScale),
            LocalLayoutDirection provides if (look.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            val frame = if (width != null) Modifier.padding(20.dp).width(width) else Modifier.fillMaxWidth().padding(vertical = 16.dp)
            Box(Modifier.background(SignerTheme.colors.page).then(frame)) { content() }
        }
    }
}

/**
 * Records every `:core:ui` catalog section in light, dark, font scale 2.0 and Arabic RTL to
 * `core/ui/build/outputs/roborazzi/ui/` for visual QA (record only; `-Psigner.screenshots=false` skips it).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h3000dp-xhdpi")
class UiCatalogScreenshotTest(private val index: Int, private val look: Look) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun section() {
        if (look.rtl) RuntimeEnvironment.setQualifiers("+ar")
        val section = UiCatalogSections[index]
        compose.mainClock.autoAdvance = false
        compose.setContent { InLook(look) { section.content() } }
        compose.mainClock.advanceTimeBy(1_000)
        val slug = section.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/ui/%02d-%s-%s.png".format(index + 1, slug, look.name.lowercase()))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> = UiCatalogSections.indices.flatMap { i -> Look.entries.map { arrayOf<Any>(i, it) } }
    }
}

/**
 * The iOS `NFT_SNAPSHOT_DIR` renders of `summaryCardRendersEngineDisplays`: every engine NFT display in the summary
 * card, light at the default size and dark at a very large text size, 353 dp wide.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h3000dp-xhdpi")
class NftSummaryScreenshotTest(private val fixture: String, private val darkLarge: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun summary() {
        val review = CatalogSamples.review(CatalogSamples.nftDisplays.getValue(fixture))
        val look = if (darkLarge) Look.Dark else Look.Light
        compose.setContent {
            val density = LocalDensity.current
            InLook(look, width = 353.dp) {
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (darkLarge) 3.1f else 1f)) {
                    ReviewSummaryCard(review, rememberReviewText())
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/nft/$fixture-${if (darkLarge) "dark-ax5" else "light"}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark{1}")
        fun parameters(): List<Array<Any>> = CatalogSamples.nftDisplays.keys.sorted().flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
    }
}
