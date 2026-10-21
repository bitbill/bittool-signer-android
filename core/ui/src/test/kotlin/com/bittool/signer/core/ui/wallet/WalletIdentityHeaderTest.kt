package com.bittool.signer.core.ui.wallet

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import com.bittool.signer.core.designsystem.theme.SignerMonospace
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** iOS `WalletIdentityHeader` (`Design/PasswordViews.swift:219-239`). */
@RunWith(RobolectricTestRunner::class)
class WalletIdentityHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * QA wallets-15: left to right the fingerprint line is the same plain text as on the Wallets list and the detail
     * ("Fingerprint 73C5DA0A"); only a right-to-left interface wraps the value in first-strong isolates.
     */
    @Test
    fun theFingerprintIsIsolatedOnlyRightToLeft() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    WalletIdentityHeader(name = "Main", fingerprint = "73c5da0a", avatarIdentity = "a3f1c2")
                }
            }
        }
        compose.onNodeWithText("Fingerprint 73C5DA0A", useUnmergedTree = true).assertExists()
        direction = LayoutDirection.Rtl
        compose.onNodeWithText("Fingerprint \u206873C5DA0A\u2069", useUnmergedTree = true).assertExists()
    }

    /**
     * QA regression-10: in Arabic the value drew in the Arabic font's proportional Latin letters while the monospaced
     * family sat on the whole sentence only. The value is a monospaced run of its own (iOS `.caption.monospaced()`),
     * left to right too, where the line looks as before.
     */
    @Test
    fun theFingerprintValueIsAMonospacedRunOfItsOwn() {
        var direction by mutableStateOf(LayoutDirection.Rtl)
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    WalletIdentityHeader(name = "Main", fingerprint = "73c5da0a", avatarIdentity = "a3f1c2")
                }
            }
        }
        fun monospacedRuns(text: String): List<String> {
            val line = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsProperties.Text].single()
            return line.spanStyles
                .filter { it.item.fontFamily == SignerMonospace }
                .map { line.text.substring(it.start, it.end) }
        }
        assertEquals(listOf("73C5DA0A"), monospacedRuns("Fingerprint \u206873C5DA0A\u2069"))
        direction = LayoutDirection.Ltr
        assertEquals(listOf("73C5DA0A"), monospacedRuns("Fingerprint 73C5DA0A"))
    }
}
