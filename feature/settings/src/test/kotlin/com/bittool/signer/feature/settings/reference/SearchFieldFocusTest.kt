package com.bittool.signer.feature.settings.reference

import android.os.Build
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Search from a hardware keyboard (keyboard mode) ends the editing with nothing focused, as iOS does: a plain
 * `clearFocus()` gave the focus to the page's first control, its back arrow, and left a focus ring there
 * (`rememberClearFocus`; QA regression-03 class).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2, Build.VERSION_CODES.BAKLAVA])
class SearchFieldFocusTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun searchWithAHardwareKeyboardLeavesNothingFocused() {
        lateinit var view: View
        var query by mutableStateOf("")
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Column {
                    Button(onClick = {}, modifier = Modifier.testTag("back")) { Text("Back") }
                    SearchField(value = query, onValueChange = { query = it }, placeholder = "Search", modifier = Modifier.testTag("search"))
                }
            }
        }
        compose.waitForIdle()
        // A key typed in the window takes it out of touch mode (the input stage's `ViewRootImpl.ensureTouchMode(false)`).
        compose.runOnUiThread {
            val root = checkNotNull(view.rootView.parent)
            root.javaClass.getDeclaredMethod("ensureTouchMode", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(root, false)
        }
        shadowOf(Looper.getMainLooper()).idle()
        val field = compose.onNodeWithTag("search")
        field.requestFocus()
        field.performTextInput("usdt")
        field.assertIsFocused()
        field.performImeAction()
        compose.waitForIdle()

        assertEquals("usdt", query)
        field.assertIsNotFocused()
        compose.onNodeWithTag("back").assertIsNotFocused()
        compose.runOnIdle { assertNull(view.rootView.findFocus(), "no focus ring on the page") }
    }
}
