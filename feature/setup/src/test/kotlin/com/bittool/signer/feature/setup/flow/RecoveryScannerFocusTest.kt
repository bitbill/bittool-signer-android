package com.bittool.signer.feature.setup.flow

import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.feature.setup.SeededRandomSource
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.testBip39
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The import step's recovery QR scanner is a window of its own (a full-screen `Dialog`, not one of the design system's
 * windows): like `SignerAlertDialog`, `SignerBottomSheet` and `SignerDropdownMenu` it holds the page's keyboard focus
 * while it is up (`WindowFocusHoldEffect`), so the page does not hand a focus ring to its first control (the back
 * arrow) while the person scans or when the scanner goes (backlog B30).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w393dp-h852dp-xhdpi")
class RecoveryScannerFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var view: View
    private lateinit var model: WalletSetupViewModel

    @Before
    fun noCameraHardware() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
    }

    /** What a hold blocks: the decor's child that holds the page (`WindowFocusHold`). */
    private val pageGroup: ViewGroup
        get() {
            val decor = view.rootView
            var child: View = view
            while ((child.parent as? View) !== decor) child = child.parent as View
            return child as ViewGroup
        }

    @Test
    fun theScannerHoldsThePagesFocusWhileItIsUp() {
        val factory = FakeRecoverySessionFactory()
        model = WalletSetupViewModel(SetupIntent.Restore, FakeSignerStore(), testBip39, SeededRandomSource(), factory, Dispatchers.Unconfined, InMemoryDeviceKeyStore())
        compose.setContent {
            view = LocalView.current
            SetupTestHost(factory) { SetupFlow(model, onFinished = {}, onCancelled = {}) }
        }
        compose.waitForIdle()
        val pageFocusability = compose.runOnIdle { pageGroup.descendantFocusability }
        assertNotEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, pageFocusability, "precondition: the page may take the focus")

        compose.runOnIdle { model.openScanner() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, pageGroup.descendantFocusability, "the scanner holds the page")
        }

        compose.runOnIdle { model.closeScanner() }
        compose.waitForIdle()
        // Released once the page's window has the window focus back, at the latest after the hold's timeout.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(pageFocusability, pageGroup.descendantFocusability, "released after the scanner") }
    }
}
