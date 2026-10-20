package com.bittool.signer.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/** The palette against the iOS values (design-system.md §2) and the WCAG rules the Android port adds (§9.2). */
class SignerColorsTest {

    private fun contrast(foreground: Color, background: Color): Double {
        val fg = foreground.compositeOver(background).luminance() + 0.05
        val bg = background.luminance() + 0.05
        return maxOf(fg, bg) / minOf(fg, bg)
    }

    @Test
    fun accentAndLabelOnAccentAreTheIosAssetColours() {
        assertEquals(Color(0xFF0F7B55), SignerColors.Light.accent)
        assertEquals(Color(0xFF6BE3A4), SignerColors.Dark.accent)
        assertEquals(Color.White, SignerColors.Light.onAccent)
        assertEquals(Color(0xFF0B1F16), SignerColors.Dark.onAccent)
        // Three distinct greens / inks (pitfall 1): the scanner mint and the tile ink are not the accent or label ink.
        assertEquals(Color(0xFFB3F0C7), SignerColors.Dark.mint)
        assertEquals(Color(0xFF0A0A0C), SignerColors.Dark.brandInk)
        assertTrue(SignerColors.Dark.mint != SignerColors.Dark.accent)
        assertTrue(SignerColors.Dark.brandInk != SignerColors.Dark.onAccent)
    }

    @Test
    fun surfacesAreTheIosGroupedColoursWithAnElevatedDarkLevel() {
        assertEquals(Color(0xFFF2F2F7), SignerColors.Light.page)
        assertEquals(Color.White, SignerColors.Light.card)
        assertEquals(Color.Black, SignerColors.Dark.page)
        assertEquals(Color(0xFF1C1C1E), SignerColors.Dark.card)
        assertEquals(Color(0xFF2C2C2E), SignerColors.Dark.raised)
        // Sheets and dialogs lift every grouped colour one step in dark mode, never in light mode.
        assertEquals(Color(0xFF1C1C1E), SignerColors.DarkElevated.page)
        assertEquals(Color(0xFF2C2C2E), SignerColors.DarkElevated.card)
        assertEquals(Color(0xFF3A3A3C), SignerColors.DarkElevated.raised)
        assertSame(SignerColors.DarkElevated, SignerColors.Dark.elevated())
        assertSame(SignerColors.DarkElevated, SignerColors.DarkElevated.elevated())
        assertSame(SignerColors.Light, SignerColors.Light.elevated())
        assertEquals(SignerColors.Light.launchBackground, SignerColors.Light.page)
        assertEquals(Color.Black, SignerColors.Dark.launchBackground)
    }

    @Test
    fun semanticTonesArePinnedSystemColours() {
        assertEquals(Color(0xFF34C759), SignerColors.Light.success)
        assertEquals(Color(0xFFFF9500), SignerColors.Light.warning)
        assertEquals(Color(0xFFFF3B30), SignerColors.Light.danger)
        assertEquals(Color(0xFF30D158), SignerColors.Dark.success)
        assertEquals(Color(0xFFFF9F0A), SignerColors.Dark.warning)
        assertEquals(Color(0xFFFF453A), SignerColors.Dark.danger)
        assertEquals(Color(0xFFFFCC00), SignerColors.Light.strengthMid)
        assertEquals(Color(0xFFFFD60A), SignerColors.Dark.strengthMid)
    }

    @Test
    fun smallTextOnATintMeetsAa() {
        // Pills, callouts and tinted boxes sit on the page or on a card (in sheets: the elevated page and card).
        for (colors in listOf(SignerColors.Light, SignerColors.Dark, SignerColors.DarkElevated)) {
            for (tone in listOf(colors.accent, colors.success, colors.warning, colors.danger)) {
                for (surface in listOf(colors.page, colors.card)) {
                    for (alpha in listOf(0.12f, 0.14f)) {
                        val tint = tone.copy(alpha = alpha).compositeOver(surface)
                        val ratio = contrast(colors.onTint(tone), tint)
                        assertTrue(ratio >= 4.5, "$colors: ${colors.onTint(tone)} on $tone@$alpha over $surface is $ratio:1")
                    }
                }
            }
        }
    }

    @Test
    fun toneTextOnARaisedBoxMeetsAa() {
        // The compact contract notice draws tone text inside the summary's raised boxes (pages, not sheets).
        for (colors in listOf(SignerColors.Light, SignerColors.Dark)) {
            for (tone in listOf(colors.accent, colors.success, colors.warning, colors.danger)) {
                val ratio = contrast(colors.onTint(tone), colors.raised)
                assertTrue(ratio >= 4.5, "$colors: ${colors.onTint(tone)} on raised is $ratio:1")
            }
        }
    }

    @Test
    fun accentAndItsLabelMeetAa() {
        for (colors in listOf(SignerColors.Light, SignerColors.Dark, SignerColors.DarkElevated)) {
            assertTrue(contrast(colors.onAccent, colors.accent) >= 4.5, "$colors onAccent")
            assertTrue(contrast(colors.accent, colors.page) >= 4.5, "$colors accent on page")
            assertTrue(contrast(colors.label, colors.card) >= 7.0, "$colors label")
        }
    }

    @Test
    fun onTintMapsEachToneAndLeavesOtherColoursAlone() {
        val c = SignerColors.Light
        assertEquals(c.accentOnTint, c.onTint(c.accent))
        assertEquals(c.successOnTint, c.onTint(c.success))
        assertEquals(c.warningOnTint, c.onTint(c.warning))
        assertEquals(c.dangerOnTint, c.onTint(c.danger))
        assertEquals(c.secondaryLabel, c.onTint(c.secondaryLabel))
    }

    @Test
    fun materialSchemeCarriesTheBrandWithoutTonalTint() {
        val light = SignerColors.Light.toColorScheme()
        assertEquals(SignerColors.Light.accent, light.primary)
        assertEquals(SignerColors.Light.onAccent, light.onPrimary)
        assertEquals(light.surface, light.surfaceTint)
        assertEquals(SignerColors.Light.page, light.background)
        val dark = SignerColors.Dark.toColorScheme()
        assertEquals(SignerColors.Dark.accent, dark.primary)
        assertEquals(Color(0xFF0B1F16), dark.onPrimary)
        val elevated = SignerColors.DarkElevated.toColorScheme()
        assertEquals(Color(0xFF1C1C1E), elevated.surface)
        assertEquals(elevated.surface, elevated.surfaceTint)
    }
}
