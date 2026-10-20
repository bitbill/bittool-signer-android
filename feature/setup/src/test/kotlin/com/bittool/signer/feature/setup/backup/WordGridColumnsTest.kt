package com.bittool.signer.feature.setup.backup

import android.content.Context
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Port of iOS `RecoveryCardsTests.wordGridsDropColumnsBeforeCuttingWordsOff` (`BitToolSignerTests/RecoveryCardsTests.
 * swift:50-73`): the grid widths of a 375-pt and a 402-pt phone (screen − 2 × 20 page padding − 2 × 12 card padding),
 * measured with the real monospaced font. Android font scales stand in for the Dynamic Type sizes: 1.0 = `.large`,
 * 1.4 ≈ `.xxxLarge` (subheadline 21 / 15), and a linear 3.3 ≈ `.accessibility5` (subheadline ≈ 49). Android's own
 * largest setting (2.0, scaled non-linearly by the platform) keeps two columns at a reduced, still legible size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordGridColumnsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val grid = WordGridColumns.Words
    private val long = listOf("abandon", "abstract", "zoo")

    /** The platform density (non-linear font scaling from 1.03 up, as on a device). */
    private fun density(fontScale: Float): Density = Density(1f, fontScale)

    /** A linear density for scales beyond Android's settings (the iOS accessibility sizes). */
    private fun linear(fontScale: Float): Density = object : Density {
        override val density = 1f
        override val fontScale = fontScale
    }

    private fun measurer(density: Density = density(1f)) = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)

    private fun count(width: Int, words: List<String>, density: Density = density(1f)): Int =
        grid.count(width, words, measurer(density), density)

    @Test
    fun wordGridsDropColumnsBeforeCuttingWordsOff() {
        assertEquals(3, count(402 - 64, long))
        assertEquals(2, count(375 - 64, long), "8-letter words must not be cut off on small phones.")
        assertEquals(3, count(375 - 64, listOf("zoo", "army")))
        assertEquals(2, count(402 - 64, long, density(1.4f)))
        assertEquals(1, count(402 - 64, long, linear(3.3f)))
        val largest = grid.layout(402 - 64, long, measurer(density(2f)), density(2f))
        assertEquals(2, largest.columns, "Android's largest font keeps two columns")
        assertTrue(largest.wordSize.value >= 15f * WordGridColumns.MINIMUM_SCALE, "$largest")

        // One size for every word: the widest word decides it, and it never exceeds the text style.
        val pro = grid.layout(402 - 64, long, measurer(), density(1f))
        val short = grid.layout(402 - 64, listOf("zoo", "army"), measurer(), density(1f))
        assertTrue(pro.wordSize.value <= short.wordSize.value && short.wordSize.value == 15f, "$pro / $short")
        assertTrue(pro.wordSize.value >= 15f * WordGridColumns.MINIMUM_SCALE, "$pro")
        val widest = measurer().measure("abstract", pro.wordStyle(TextStyle.Default), softWrap = false, maxLines = 1).size.width
        val cell = (402 - 64 - 2 * 8) / 3f
        assertTrue(widest <= cell - 2 * 10 - 18 - 6, "The shared size fits the widest word in its cell ($widest).")

        // Before layout (width 0) the old default applies.
        assertEquals(3, count(0, long))
        assertEquals(2, count(0, long, density(1.6f)))
    }

    @Test
    fun theConcealedPlaceholderAssumesEightLetterWordsWhateverThePhrase() {
        // The placeholder's columns come from "wwwwwwww", never from the real words: an 8-letter reference, so the
        // revealed grid of a typical phrase has the same shape and the placeholder leaks nothing but the word count.
        val reference = listOf(WordGridColumns.REFERENCE_WORD)
        assertEquals(3, count(402 - 64, reference))
        assertEquals(2, count(375 - 64, reference))
        assertEquals(count(375 - 64, long), count(375 - 64, reference))
    }

    @Test
    fun theCardsAndTheCheckUseTheirOwnPaddingAndIndexSamples() {
        val cards = WordGridColumns.Cards.layout(402 - 64, long + "xxxx", measurer(), density(1f))
        val check = WordGridColumns.Verification.layout(402 - 64, listOf("abandon", "zoo", "••••••"), measurer(), density(1f))
        assertTrue(cards.columns in 1..3 && check.columns in 1..3)
        assertEquals(3, check.columns, "short chip words keep three columns")
    }
}
