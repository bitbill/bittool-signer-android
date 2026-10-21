package com.bittool.signer.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.navigation.ActivityGraph
import com.bittool.signer.core.navigation.ActivityRoute
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.ScanGraph
import com.bittool.signer.core.navigation.ScanRoute
import com.bittool.signer.core.navigation.SettingsGraph
import com.bittool.signer.core.navigation.SettingsRoute
import com.bittool.signer.core.navigation.WalletsGraph
import com.bittool.signer.core.navigation.WalletsRoute
import com.bittool.signer.lock.AppLockContent
import com.bittool.signer.lock.AppLockTags
import com.bittool.signer.lock.AppLockUiState
import com.bittool.signer.lock.LockWallet
import com.bittool.signer.main.MainScaffold
import com.bittool.signer.root.LoadFailureScreen
import com.bittool.signer.root.PrivacyCover
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The looks every shell screen is recorded in. */
enum class Look(val dark: Boolean, val fontScale: Float = 1f, val rtl: Boolean = false) {
    Light(dark = false),
    Dark(dark = true),
    FontScale2(dark = false, fontScale = 2f),
    Rtl(dark = true, rtl = true),
}

/**
 * Records the shell's own screens — App Lock (biometric and password), "Wallets Unavailable", the privacy cover while
 * recording, the tab bar with the Scan badge — to `app/build/outputs/roborazzi/` for visual QA (record only, no golden
 * comparison; `-Psigner.screenshots=false` skips them). The tab pages are empty stand-ins (they belong to the features).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ShellScreenshotTest(private val look: Look) {
    @get:Rule
    val compose = createComposeRule()

    private val wallets = listOf(LockWallet(WalletId.random(), "Public fixture 1"), LockWallet(WalletId.random(), "Travel Signer"))

    @Test
    fun appLockBiometric() {
        render {
            Lock(
                AppLockUiState(
                    wallets = wallets,
                    biometricsAvailable = true,
                    error = UiText.Verbatim("Biometric authentication was cancelled. Use a wallet password to continue."),
                ),
            )
        }
        capture("app-lock")
    }

    @Test
    fun appLockPassword() {
        render {
            Lock(
                AppLockUiState(
                    usePassword = true,
                    wallets = wallets,
                    selectedWallet = wallets.first().id,
                    biometricsAvailable = true,
                    error = UiText.Ios("The password is incorrect."),
                ),
            )
        }
        capture("app-lock-password")
    }

    /** The wallet menu open (a popup window of its own, so every window is captured): "Choose a wallet", then the names. */
    @Test
    fun appLockWalletMenu() {
        render {
            Lock(AppLockUiState(usePassword = true, wallets = wallets, selectedWallet = wallets.last().id))
        }
        compose.onNodeWithTag(AppLockTags.WALLET).performClick()
        // The popup window is drawn only once its own frames have run (a paused clock leaves it blank).
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag(AppLockTags.NO_WALLET).assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/shell/app-lock-wallet-menu-${look.name.lowercase()}.png")
    }

    @Test
    fun loadFailure() {
        render { LoadFailureScreen(retrying = false, onRetry = {}) }
        capture("load-failure")
    }

    @Test
    fun privacyCoverWhileRecorded() {
        render { PrivacyCover(visible = true, captured = true) }
        capture("privacy-cover-captured")
    }

    @Test
    fun tabsWithAWaitingRequest() {
        renderTabs()
        capture("tabs")
    }

    /** Wide windows (tablets, landscape) get the navigation rail: the iPad layout. */
    @Test
    @Config(qualifiers = "w900dp-h600dp-xhdpi")
    fun tabsOnAWideWindow() {
        renderTabs()
        capture("tabs-wide")
    }

    private fun renderTabs() {
        render {
            MainScaffold(
                router = remember { AppRouter() },
                showsScanBadge = true,
                destinations = {
                    navigation<WalletsGraph>(startDestination = WalletsRoute) { composable<WalletsRoute> { Box(Modifier.fillMaxSize()) } }
                    navigation<ScanGraph>(startDestination = ScanRoute) { composable<ScanRoute> { } }
                    navigation<ActivityGraph>(startDestination = ActivityRoute) { composable<ActivityRoute> { } }
                    navigation<SettingsGraph>(startDestination = SettingsRoute) { composable<SettingsRoute> { } }
                },
            )
        }
    }

    @Composable
    private fun Lock(state: AppLockUiState) {
        AppLockContent(
            state = state,
            password = remember { TextFieldState() },
            focusRequester = remember { FocusRequester() },
            onBiometrics = {},
            onUsePassword = {},
            onUseBiometrics = {},
            onSelectWallet = {},
            onUnlock = {},
        )
    }

    private fun render(content: @Composable () -> Unit) {
        if (look.rtl) RuntimeEnvironment.setQualifiers("+ar")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            SignerTheme(darkTheme = look.dark, applySystemBarAppearance = false) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, look.fontScale),
                    LocalLayoutDirection provides if (look.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/shell/$name-${look.name.lowercase()}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = Look.entries.map { arrayOf<Any>(it) }
    }
}
