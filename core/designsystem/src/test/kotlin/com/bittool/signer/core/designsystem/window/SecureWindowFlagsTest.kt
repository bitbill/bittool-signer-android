package com.bittool.signer.core.designsystem.window

import android.content.pm.ApplicationInfo
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.window.SecureFlagPolicy
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.component.SignerBottomSheet
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * QA sign-10: the debug screenshot tour (`bts.uitest.allowCapture`) leaves the activity window capturable, and the
 * secure sheets and dialogs (Paste, Authorize, Recovery Cards) must follow it, or the tour documents empty frames. In
 * every other case — a release build, an activity that is `FLAG_SECURE` — a secure window stays `FLAG_SECURE`.
 */
@RunWith(RobolectricTestRunner::class)
class SecureWindowFlagsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var info: ApplicationInfo
    private var originalFlags = 0

    @Before
    fun saveFlags() {
        info = RuntimeEnvironment.getApplication().applicationInfo
        originalFlags = info.flags
    }

    @After
    fun restoreFlags() {
        info.flags = originalFlags
    }

    private fun debuggable(on: Boolean) {
        info.flags = if (on) info.flags or ApplicationInfo.FLAG_DEBUGGABLE else info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }

    @Test
    fun theRuleOnlyLetsADebugBuildWithACapturableHostOff() {
        val debug = ApplicationInfo.FLAG_DEBUGGABLE
        val secureHost = WindowManager.LayoutParams.FLAG_SECURE
        assertEquals(SecureFlagPolicy.Inherit, secureFlagPolicy(secure = false, applicationFlags = 0, hostWindowFlags = 0))
        assertEquals(SecureFlagPolicy.SecureOn, secureFlagPolicy(secure = true, applicationFlags = 0, hostWindowFlags = 0))
        assertEquals(SecureFlagPolicy.SecureOn, secureFlagPolicy(secure = true, applicationFlags = debug, hostWindowFlags = secureHost))
        assertEquals(SecureFlagPolicy.SecureOn, secureFlagPolicy(secure = true, applicationFlags = debug, hostWindowFlags = null))
        assertEquals(SecureFlagPolicy.Inherit, secureFlagPolicy(secure = true, applicationFlags = debug, hostWindowFlags = 0))
    }

    @Test
    fun inTheScreenshotTourSecureWindowsCanBeCaptured() {
        debuggable(true)
        // The tour: `:app` did not set FLAG_SECURE on the activity window.
        val (dialog, sheet) = showSecureWindows()
        assertFalse(dialog.isSecure(), "dialog follows the capturable activity")
        assertFalse(sheet.isSecure(), "sheet follows the capturable activity")
    }

    @Test
    fun aDebugBuildWithASecureActivityKeepsSecureWindowsSecure() {
        debuggable(true)
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        val (dialog, sheet) = showSecureWindows()
        assertTrue(dialog.isSecure(), "dialog")
        assertTrue(sheet.isSecure(), "sheet")
    }

    @Test
    fun aReleaseBuildKeepsSecureWindowsSecureWhateverTheActivity() {
        debuggable(false)
        val (dialog, sheet) = showSecureWindows()
        assertTrue(dialog.isSecure(), "dialog")
        assertTrue(sheet.isSecure(), "sheet")
    }

    private fun showSecureWindows(): Pair<View, View> {
        var dialogView: View? = null
        var sheetView: View? = null
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                SignerAlertDialog(
                    title = "Wallet Password",
                    text = null,
                    confirm = DialogAction("OK") {},
                    secure = true,
                    onDismissRequest = {},
                ) { dialogView = LocalView.current }
                SignerBottomSheet(onDismissRequest = {}, secure = true) {
                    sheetView = LocalView.current
                    Text("Paste Request")
                }
            }
        }
        compose.waitForIdle()
        return assertNotNull(dialogView, "dialog composed") to assertNotNull(sheetView, "sheet composed")
    }

    private fun View.isSecure(): Boolean {
        val window = assertNotNull(dialogWindow(), "a window of its own")
        return window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }
}
