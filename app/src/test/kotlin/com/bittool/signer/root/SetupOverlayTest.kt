package com.bittool.signer.root

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.navigation.ConnectRoute
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.navigation.SetupPresentation
import com.bittool.signer.core.navigation.WalletRoute
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The setup presentation (iOS RootView `.sheet(item: router.setup)`, AppRouter `finishSetup` / `setupDidDismiss`;
 * app-shell.md §7): the introduction first when asked, then the steps; the route to open is handed over only after
 * the overlay has gone.
 */
@RunWith(RobolectricTestRunner::class)
class SetupOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    private var presentation by mutableStateOf<SetupPresentation?>(null)
    private val log = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                SetupOverlay(
                    presentation = presentation,
                    onIntroductionCompleted = { log += "introduction completed" },
                    onFinished = { route: WalletRoute? ->
                        log += "finished $route"
                        presentation = null
                    },
                    onCancelled = {
                        log += "cancelled"
                        presentation = null
                    },
                    onDismissed = { log += "dismissed" },
                    introduction = { _, complete, _ ->
                        Button(onClick = complete, modifier = Modifier.testTag("intro")) { Text("Get Started") }
                    },
                    steps = { p, finished, cancelled ->
                        Column {
                            Text("steps ${p.intent}", Modifier.testTag("steps"))
                            Button(onClick = { finished(ConnectRoute.walletLevel(wallet)) }, modifier = Modifier.testTag("pair")) { Text("Pair") }
                            Button(onClick = cancelled, modifier = Modifier.testTag("cancel")) { Text("Cancel") }
                        }
                    },
                )
            }
        }
    }

    private val wallet = WalletId.random()

    @Test
    fun theIntroductionLeadsIntoTheStepsOfTheSameIntent() {
        show()
        compose.onNodeWithTag(RootTags.SETUP).assertDoesNotExist()
        presentation = SetupPresentation(id = 1, intent = SetupIntent.Restore, showsIntroduction = true)
        compose.waitForIdle()
        compose.onNodeWithTag("steps").assertDoesNotExist()
        compose.onNodeWithTag("intro").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("steps").assertExists()
        assertEquals(listOf("introduction completed"), log)
    }

    @Test
    fun withoutTheIntroductionTheStepsShowAtOnce() {
        show()
        presentation = SetupPresentation(id = 2, intent = SetupIntent.Create, showsIntroduction = false)
        compose.waitForIdle()
        compose.onNodeWithTag("steps").assertExists()
        compose.onNodeWithTag("intro").assertDoesNotExist()
    }

    @Test
    fun theRouteToOpenIsHandedOverOnlyAfterTheOverlayHasGone() {
        show()
        presentation = SetupPresentation(id = 3, intent = SetupIntent.Create, showsIntroduction = false)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("pair").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf("finished ${ConnectRoute.walletLevel(wallet)}"), log, "still sliding away")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf("finished ${ConnectRoute.walletLevel(wallet)}", "dismissed"), log)
        compose.onNodeWithTag(RootTags.SETUP).assertDoesNotExist()
    }

    @Test
    fun cancellingDismissesToo() {
        show()
        presentation = SetupPresentation(id = 4, intent = SetupIntent.Restore, showsIntroduction = false)
        compose.waitForIdle()
        compose.onNodeWithTag("cancel").performClick()
        compose.waitForIdle()
        assertEquals(listOf("cancelled", "dismissed"), log)
    }
}
