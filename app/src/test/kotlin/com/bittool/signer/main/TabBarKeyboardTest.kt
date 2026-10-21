package com.bittool.signer.main

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.navigation.ActivityGraph
import com.bittool.signer.core.navigation.ActivityRoute
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.ScanGraph
import com.bittool.signer.core.navigation.ScanRoute
import com.bittool.signer.core.navigation.SettingsGraph
import com.bittool.signer.core.navigation.SettingsRoute
import com.bittool.signer.core.navigation.Tab
import com.bittool.signer.core.navigation.WalletsGraph
import com.bittool.signer.core.navigation.WalletsRoute
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowViewRootImpl

/**
 * The tab bar slides away for the keyboard only while the keyboard is this window's (QA regression-04): the IME inset
 * also reaches the activity window under a dialog, a sheet or the lock window that has the keyboard, and hiding the bar
 * there made the page grow and shrink behind "Rename Wallet". The window manager's signals are sent on the real
 * `ViewRootImpl` paths: a window above takes the window focus before its keyboard can come up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class TabBarKeyboardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var view: View

    private fun show() {
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                MainScaffold(router = AppRouter(), showsScanBadge = false, navController = rememberNavController()) {
                    navigation<WalletsGraph>(startDestination = WalletsRoute) { composable<WalletsRoute> { Text("wallets", Modifier.testTag("page")) } }
                    navigation<ScanGraph>(startDestination = ScanRoute) { composable<ScanRoute> { Text("scan") } }
                    navigation<ActivityGraph>(startDestination = ActivityRoute) { composable<ActivityRoute> { Text("activity") } }
                    navigation<SettingsGraph>(startDestination = SettingsRoute) { composable<SettingsRoute> { Text("settings") } }
                }
            }
        }
        compose.waitForIdle()
        windowFocus(true)
    }

    /** The activity window gains or loses the window focus (a dialog, a sheet or the lock window above it). */
    private fun windowFocus(focused: Boolean) {
        compose.runOnUiThread { Shadow.extract<ShadowViewRootImpl>(view.rootView.parent).callWindowFocusChanged(focused) }
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    /** The insets the window gets while a keyboard (of any window) is up, or once it is gone. */
    private fun keyboard(shown: Boolean) {
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if (shown) 300 else 0))
                .setVisible(WindowInsetsCompat.Type.ime(), shown)
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle()
    }

    private fun barShown(): Boolean = compose.onAllNodesWithTag(Tab.Wallets.testTag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun thePagesOwnKeyboardHidesTheBar() {
        show()
        assertTrue(barShown(), "precondition")
        keyboard(shown = true)
        assertFalse(barShown(), "a field of the page has the keyboard")
        keyboard(shown = false)
        assertTrue(barShown())
    }

    @Test
    fun aDialogsKeyboardLeavesTheBarInPlace() {
        show()
        // "Rename Wallet": the dialog window takes the focus, then its field brings the keyboard up.
        windowFocus(false)
        keyboard(shown = true)
        assertTrue(barShown(), "the dialog's keyboard is not the page's")
        // Saved: the page gets the focus back while the dialog's keyboard is still going away.
        windowFocus(true)
        assertTrue(barShown(), "no flash of the bar going away")
        keyboard(shown = false)
        assertTrue(barShown())
    }

    @Test
    fun aDialogOverThePagesKeyboardKeepsTheBarAwayUntilTheKeyboardGoes() {
        show()
        keyboard(shown = true)
        assertFalse(barShown())
        // A dialog opens while the page's keyboard is up and keeps it: the page behind does not change.
        windowFocus(false)
        assertFalse(barShown())
        windowFocus(true)
        keyboard(shown = false)
        assertTrue(barShown())
    }
}
