package com.bittool.signer.core.ui.wallet

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.bittool.signer.core.model.CompatibleWallets
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** iOS `WalletAvatar` (`Design/Logos.swift:187-220`) and the bundled wallet logos. */
@RunWith(RobolectricTestRunner::class)
class WalletAvatarTest {

    /** Golden values computed with the Swift formula (SHA-256 → HSB) by an independent Python implementation. */
    @Test
    fun gradientFollowsTheSwiftFormula() {
        val cases = mapOf(
            "5f1a6d8c2b9e4f70a1d3c6b8e2f4a7c9d0b1e3f5a7c9d1e3f5a7b9c1d3e5f7a9" to (0x4CC765 to 0x2F9B9E),
            "3F2504E0-4F89-41D3-9A0C-0305E82C3301" to (0xC74CC7 to 0x9E2F3C),
            "a3f1c2" to (0xC74C68 to 0x9E662F),
        )
        for ((identity, expected) in cases) {
            val colors = WalletAvatarColors.of(identity)
            assertRgb(expected.first, colors.start, identity)
            assertRgb(expected.second, colors.end, identity)
        }
    }

    private fun assertRgb(expected: Int, actual: Color, label: String) {
        val argb = actual.toArgb()
        val channels = listOf(16, 8, 0).map { shift -> abs(((argb shr shift) and 0xFF) - ((expected shr shift) and 0xFF)) }
        assertTrue(channels.all { it <= 1 }, "$label: expected ${expected.toString(16)}, was ${(argb and 0xFFFFFF).toString(16)}")
    }

    @Test
    fun theInitialIsTheFirstCharacterOfTheTrimmedName() {
        assertEquals("M", WalletAvatarColors.initial("  main  "))
        assertEquals("W", WalletAvatarColors.initial(""))
        assertEquals("W", WalletAvatarColors.initial(" \n\t"))
        assertEquals("冷", WalletAvatarColors.initial("冷钱包"))
        // A grapheme, not a UTF-16 unit; upper-casing is locale-independent like Swift's.
        assertEquals("👍🏽", WalletAvatarColors.initial("👍🏽 wallet"))
        assertEquals("I", WalletAvatarColors.initial("işlem"))
        assertEquals("SS", WalletAvatarColors.initial("ßavings"))
    }

    @Test
    fun everyCompatibleWalletHasItsBundledLogo() {
        for (asset in CompatibleWallets.allAssets) assertNotNull(walletIconRes(asset), asset)
        assertEquals(24, CompatibleWallets.allAssets.size)
        assertNull(walletIconRes("wallet_unknown"))
        assertNull(walletIconRes(null))
    }

    @Test
    fun valuesAreIsolatedInsideSentences() {
        assertEquals(listOf(0x2068) + "73C5DA0A".map { it.code } + 0x2069, isolate("73C5DA0A").map { it.code })
    }
}
