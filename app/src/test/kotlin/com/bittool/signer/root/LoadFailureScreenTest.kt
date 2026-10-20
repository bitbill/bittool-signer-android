package com.bittool.signer.root

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Wallets Unavailable" (iOS `LoadFailureView`, RootView.swift:92-113). */
@RunWith(RobolectricTestRunner::class)
class LoadFailureScreenTest {
    @get:Rule
    val compose = createComposeRule()

    /** Review fix: the page did not scroll, so on a landscape phone with large text "Try Again" was cut off. */
    @Test
    @Config(qualifiers = "w640dp-h320dp")
    fun tryAgainStaysReachableWithLargeTextOnAShortWindow() {
        var retries = 0
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    LoadFailureScreen(retrying = false, onRetry = { retries++ })
                }
            }
        }
        compose.onNodeWithText("Wallets Unavailable").assertIsDisplayed()
        compose.onNodeWithTag(RootTags.RETRY).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun aRetryInProgressRefusesASecondTap() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) { LoadFailureScreen(retrying = true, onRetry = {}) }
        }
        compose.onNodeWithTag(RootTags.RETRY).assertIsNotEnabled()
    }
}
