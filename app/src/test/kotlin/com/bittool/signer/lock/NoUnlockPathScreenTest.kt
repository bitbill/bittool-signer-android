package com.bittool.signer.lock

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.security.AppAccessState
import com.bittool.signer.core.security.testing.FakeAppAccessController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** QA L2 on screen: the lock screen with no unlock path left says why and offers "Continue Without App Lock". */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp", sdk = [34])
class NoUnlockPathScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val wallet = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)
    private val store = FakeSignerStore(SignerState(isLoading = false, walletLoadSucceeded = true, wallets = listOf(wallet)))
    private val access = FakeAppAccessController(
        AppAccessState(resolving = false, isEnabled = true, isLocked = true, biometricsAvailable = false),
    )

    @Test
    fun theExplanationReplacesThePasswordFormAndContinueOpensTheApp() {
        access.noUnlockPath = true
        val viewModel = AppLockViewModel(access, store)
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) { AppLockScreen(viewModel) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(AppLockTags.NO_UNLOCK_PATH).assertExists()
        compose.onNodeWithText("No Way to Unlock").assertExists()
        compose.onNodeWithTag(AppLockTags.UNLOCK).assertDoesNotExist()
        compose.onNodeWithTag(AppLockTags.BIOMETRIC).assertDoesNotExist()

        compose.onNodeWithText("Continue Without App Lock").performClick()
        compose.waitForIdle()
        assertEquals(1, access.continueCalls)
        assertFalse(access.state.value.isLocked)
        assertFalse(access.state.value.isEnabled)
    }

    @Test
    fun aLockWithAWayInShowsNoExplanation() {
        val viewModel = AppLockViewModel(access, store)
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) { AppLockScreen(viewModel) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(AppLockTags.NO_UNLOCK_PATH).assertDoesNotExist()
        compose.onNodeWithTag(AppLockTags.UNLOCK).assertExists()
    }
}
