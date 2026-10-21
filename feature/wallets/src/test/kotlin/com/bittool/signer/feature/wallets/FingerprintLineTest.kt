package com.bittool.signer.feature.wallets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.theme.SignerMonospace
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.feature.wallets.common.WalletIdentity
import com.bittool.signer.feature.wallets.detail.WalletDetailActions
import com.bittool.signer.feature.wallets.detail.WalletDetailContent
import com.bittool.signer.feature.wallets.detail.WalletDetailUiState
import com.bittool.signer.feature.wallets.list.WalletsActions
import com.bittool.signer.feature.wallets.list.WalletsContent
import com.bittool.signer.feature.wallets.list.WalletsUiState
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Wallets card and the wallet page draw their "Fingerprint 73C5DA0A" line through core:ui's `fingerprintLine`
 * (QA production-07, regression-06, regression-10), like the security pages' identity header: the same plain text left
 * to right (the e2e suite matches it), the value isolated right to left, and the value itself in the monospaced family
 * (iOS `.caption.monospaced()` / `.footnote.monospaced()`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp-xhdpi")
class FingerprintLineTest {
    @get:Rule
    val compose = createComposeRule()

    private var direction by mutableStateOf(LayoutDirection.Ltr)

    private val identity = WalletFixtures.state().let { WalletIdentity.of(it, WalletFixtures.readyId)!! }

    private fun content(body: @Composable () -> Unit) = compose.setContent {
        SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) { body() }
        }
    }

    /** The parts of the line [text] drawn in the monospaced family. */
    private fun monospacedRuns(text: String): List<String> {
        val line = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        return line.spanStyles.filter { it.item.fontFamily == SignerMonospace }.map { line.text.substring(it.start, it.end) }
    }

    private fun assertTheLineInBothDirections() {
        assertEquals(listOf("73C5DA0A"), monospacedRuns("Fingerprint 73C5DA0A"))
        direction = LayoutDirection.Rtl
        compose.waitForIdle()
        assertEquals(listOf("73C5DA0A"), monospacedRuns("Fingerprint \u206873C5DA0A\u2069"))
    }

    @Test
    fun theWalletCardsFingerprintValueIsMonospacedAndIsolatedOnlyRightToLeft() {
        content { WalletsContent(WalletsUiState(listOf(identity), loaded = true), WalletsActions()) }
        assertTheLineInBothDirections()
    }

    @Test
    fun theWalletPagesFingerprintValueIsMonospacedAndIsolatedOnlyRightToLeft() {
        content {
            WalletDetailContent(
                uiState = WalletDetailUiState(walletId = WalletFixtures.readyId, identity = identity),
                actions = WalletDetailActions(),
                toast = rememberStatusToastState(),
                onCopyFingerprint = {},
                onRename = {},
            )
        }
        assertTheLineInBothDirections()
    }
}
