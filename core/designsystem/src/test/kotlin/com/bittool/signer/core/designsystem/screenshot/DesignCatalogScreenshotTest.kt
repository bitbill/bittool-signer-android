package com.bittool.signer.core.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.bittool.signer.core.designsystem.catalog.CatalogSection
import com.bittool.signer.core.designsystem.catalog.DesignCatalogSections
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The four looks every component is checked in (design-system.md §9.14). */
enum class ScreenshotVariant(val dark: Boolean, val fontScale: Float, val rtl: Boolean) {
    Light(dark = false, fontScale = 1f, rtl = false),
    Dark(dark = true, fontScale = 1f, rtl = false),
    FontScale2(dark = false, fontScale = 2f, rtl = false),
    Rtl(dark = true, fontScale = 1f, rtl = true),
}

/**
 * Records every design-catalog section of `:core:designsystem` in light, dark, font scale 2.0 and Arabic RTL to
 * `core/designsystem/build/outputs/roborazzi/` for visual QA (record only, no golden comparison; skipped with
 * `-Psigner.screenshots=false`). The compose clock is driven by hand: busy buttons spin forever, so the test never
 * waits for the UI to become idle with a running clock.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h2400dp-xhdpi")
class DesignCatalogScreenshotTest(private val index: Int, private val variant: ScreenshotVariant) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun section() {
        val section = DesignCatalogSections[index]
        capture(section, variant, "designsystem")
    }

    private fun capture(section: CatalogSection, variant: ScreenshotVariant, area: String) {
        if (variant.rtl) RuntimeEnvironment.setQualifiers("+ar")
        compose.mainClock.autoAdvance = false
        compose.setContent { Variant(variant) { section.content() } }
        compose.mainClock.advanceTimeBy(1_000)
        val slug = section.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val name = "%02d-%s-%s".format(index + 1, slug, variant.name.lowercase())
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$area/$name.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> =
            DesignCatalogSections.indices.flatMap { i -> ScreenshotVariant.entries.map { arrayOf<Any>(i, it) } }
    }
}

/** Applies [variant] around [content] on the page colour. */
@Composable
fun Variant(variant: ScreenshotVariant, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    SignerTheme(darkTheme = variant.dark, applySystemBarAppearance = false) {
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, variant.fontScale),
            LocalLayoutDirection provides if (variant.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            Box(Modifier.fillMaxWidth().background(SignerTheme.colors.page).padding(vertical = 16.dp)) { content() }
        }
    }
}
