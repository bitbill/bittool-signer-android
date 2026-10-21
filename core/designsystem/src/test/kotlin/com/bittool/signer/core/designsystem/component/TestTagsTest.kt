package com.bittool.signer.core.designsystem.component

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.window.exposesTestTags
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The e2e hooks of the shared bar and windows (gaps.md G1.0; backlog B3, B4). */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
class TestTagsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun content(body: @Composable () -> Unit) {
        compose.setContent { SignerTheme(darkTheme = false, applySystemBarAppearance = false) { Box { body() } } }
    }

    @Test
    fun theBackArrowIsTaggedNavBackAndTheCrossNavClose() {
        var navigation by mutableStateOf(NavIcon.Back)
        var taps = 0
        content { SignerTopAppBar(title = "Addresses", navigation = navigation, onNavigate = { taps++ }) }
        compose.onNodeWithTag(NavIconTags.BACK).assertContentDescriptionEquals("Back").performClick()
        assertEquals(1, taps)
        compose.onNodeWithTag(NavIconTags.CLOSE).assertDoesNotExist()

        navigation = NavIcon.Close
        compose.onNodeWithTag(NavIconTags.CLOSE).assertContentDescriptionEquals("Close").performClick()
        assertEquals(2, taps)
        compose.onNodeWithTag(NavIconTags.BACK).assertDoesNotExist()

        navigation = NavIcon.None
        compose.onNodeWithTag(NavIconTags.BACK).assertDoesNotExist()
        compose.onNodeWithTag(NavIconTags.CLOSE).assertDoesNotExist()
        assertEquals("nav.back", NavIconTags.BACK)
    }

    /** The first setup step's cross: its own tag and label, disabled while busy. */
    @Test
    fun theNavigationIconTakesACallersTagLabelAndEnabledState() {
        var taps = 0
        content {
            SignerTopAppBar(
                title = "Create Wallet",
                navigation = NavIcon.Close,
                onNavigate = { taps++ },
                navigationTag = "setup.cancel",
                navigationLabel = "Cancel",
                navigationEnabled = false,
            )
        }
        compose.onNodeWithTag("setup.cancel").assertContentDescriptionEquals("Cancel").assertIsNotEnabled().performClick()
        assertEquals(0, taps)
        compose.onNodeWithTag(NavIconTags.CLOSE).assertDoesNotExist()
    }

    /** Sheets and dialogs are windows of their own: they publish their tags as resource ids themselves (debug only). */
    @Test
    fun dialogsAndSheetsPublishTheirTestTagsInDebuggableBuilds() {
        debuggable(true)
        try {
            var sheet by mutableStateOf(false)
            content {
                if (sheet) {
                    SignerBottomSheet(onDismissRequest = {}, testTag = "tips.sheet") { Text("Done", Modifier.testTag("tips.done")) }
                } else {
                    SignerAlertDialog(
                        title = "Reject this request?",
                        text = null,
                        confirm = DialogAction("Reject Request", destructive = true, testTag = "signing.reject.confirm") {},
                        dismiss = DialogAction("Cancel", testTag = "signing.reject.cancel") {},
                        onDismissRequest = {},
                    ) { Text("Details", Modifier.testTag("dialog.content")) }
                }
            }
            publishesResourceId("signing.reject.confirm").assertExists()
            publishesResourceId("signing.reject.cancel").assertExists()
            publishesResourceId("dialog.content").assertExists()
            sheet = true
            compose.waitForIdle()
            publishesResourceId("tips.sheet").assertExists()
            publishesResourceId("tips.done").assertExists()
        } finally {
            debuggable(false)
        }
    }

    /**
     * Backlog B26: a pop-up menu is a window of its own too (ProfileMenu, the App Lock wallet menu, Copy Address), so
     * its items' tags were invisible to UiAutomator. `SignerDropdownMenu` publishes them in debuggable builds.
     */
    @Test
    fun dropdownMenusPublishTheirTestTagsInDebuggableBuilds() {
        debuggable(true)
        try {
            var expanded by mutableStateOf(false)
            content {
                SignerDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Travel Signer") }, onClick = {}, modifier = Modifier.testTag("appLock.wallet.1"))
                }
            }
            compose.onNodeWithTag("appLock.wallet.1").assertDoesNotExist()
            expanded = true
            compose.waitForIdle()
            publishesResourceId("appLock.wallet.1").assertExists()
        } finally {
            debuggable(false)
        }
    }

    @Test
    fun releaseMenusPublishNoTestTags() {
        debuggable(false)
        var shown by mutableStateOf(true)
        content {
            SignerDropdownMenu(expanded = shown, onDismissRequest = { shown = false }) {
                DropdownMenuItem(text = { Text("Copy Address") }, onClick = {}, modifier = Modifier.testTag("address.copy"))
            }
        }
        compose.onNodeWithTag("address.copy").assertExists()
        publishesResourceId("address.copy").assertDoesNotExist()
    }

    @Test
    fun releaseBuildsPublishNoTestTags() {
        debuggable(false)
        content {
            SignerAlertDialog(
                title = "Clear Activity?",
                text = null,
                confirm = DialogAction("Clear", testTag = "activity.clear.confirm") {},
                onDismissRequest = {},
            )
        }
        compose.onNodeWithTag("activity.clear.confirm").assertExists()
        publishesResourceId("activity.clear.confirm").assertDoesNotExist()
        assertTrue(exposesTestTags(ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_HAS_CODE))
        assertFalse(exposesTestTags(ApplicationInfo.FLAG_HAS_CODE))
    }

    /** The node tagged [tag], if Compose maps its tag to a resource id: the nearest flag on it or an ancestor is true. */
    private fun publishesResourceId(tag: String) =
        compose.onNode(hasTestTag(tag) and publishedAsResourceId, useUnmergedTree = true)

    private val publishedAsResourceId = SemanticsMatcher("publishes its test tag as a resource id") { node ->
        generateSequence(node) { it.parent }
            .firstOrNull { SemanticsPropertiesAndroid.TestTagsAsResourceId in it.config }
            ?.config?.get(SemanticsPropertiesAndroid.TestTagsAsResourceId) == true
    }

    private fun debuggable(on: Boolean) {
        val info = RuntimeEnvironment.getApplication().applicationInfo
        info.flags = if (on) info.flags or ApplicationInfo.FLAG_DEBUGGABLE else info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }
}
