package com.bittool.signer.core.scanner

import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.bittool.signer.core.designsystem.window.LocalWindowGateOpen
import com.bittool.signer.core.model.QrMatrix
import com.bittool.signer.core.scanner.testing.urFrame
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

/**
 * iOS `QRCodeView` behaviour (`Design/QRCodeView.swift:29-111`; scan-sign.md §15.1), the Android window effects
 * (keep-screen-on only while an animated code plays: QA audit A02) and the per-screen frame cache (A13).
 */
@RunWith(RobolectricTestRunner::class)
class QrCodeViewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val frames = (1..3).map { urFrame(it, 3, 120) }
    private val encoder = ZxingQrCodeEncoder()
    private fun prepared(frames: List<String>): List<QrMatrix> = frames.map(encoder::encode)

    private val plate get() = compose.onNodeWithTag(QrCodeViewTags.PLATE)
    private val hasDescription = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)

    /** With the clock paused: deliver pending state writes (clicks, test writes), then draw one frame. */
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun aSingleFrameIsEncodedOffTheMainThreadAndLabelledQrCode() {
        compose.setContent { QrCodeView(frames = listOf("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")) }
        compose.waitUntil(5_000) { plate.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription) }
        plate.assertContentDescriptionEquals("QR code")
        compose.onNodeWithTag(QrCodeViewTags.PLAY_PAUSE).assertDoesNotExist()
    }

    @Test
    fun animatedFramesAdvanceEveryIntervalAndPauseAnnouncesThePart() {
        compose.mainClock.autoAdvance = false
        val matrices = prepared(frames)
        compose.setContent { QrCodeView(frames = frames, initial = matrices) }
        compose.mainClock.advanceTimeByFrame()
        // Prepared matrices show at once: nothing to wait for during a navigation push.
        plate.assert(hasDescription)
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("1/3")
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("2/3")
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("3/3")
        // While running, TalkBack's label stays put (it is not re-announced four times a second).
        plate.assertContentDescriptionEquals("Animated QR code, part 1 of 3")
        compose.onNodeWithTag(QrCodeViewTags.PLAY_PAUSE).assertContentDescriptionEquals("Pause QR animation").performClick()
        settle()
        plate.assertContentDescriptionEquals("Animated QR code, part 3 of 3")
        compose.onNodeWithTag(QrCodeViewTags.PLAY_PAUSE).assertContentDescriptionEquals("Play QR animation")
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("3/3")
        compose.onNodeWithTag(QrCodeViewTags.PLAY_PAUSE).performClick()
        settle()
        compose.onNodeWithTag(QrCodeViewTags.PLAY_PAUSE).assertContentDescriptionEquals("Pause QR animation")
        // One interval after playing again (plus the frames that restart the ticker), the code wraps to part 1.
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("3/3")
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("1/3")
    }

    @Test
    fun newFramesRestartAtTheFirstPartWhilePauseIsKept() {
        compose.mainClock.autoAdvance = false
        val next = (1..4).map { urFrame(it, 4, 140) }
        val matrices = mapOf(frames to prepared(frames), next to prepared(next))
        var shown by mutableStateOf(frames)
        compose.setContent { QrCodeView(frames = shown, initial = matrices.getValue(shown)) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag(QrCodeViewTags.PLAY_PAUSE).performClick()
        settle()
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("2/3")
        shown = next
        settle()
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("1/4")
        compose.onNodeWithTag(QrCodeViewTags.PLAY_PAUSE).assertContentDescriptionEquals("Play QR animation")
        compose.mainClock.advanceTimeBy(750)
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("1/4")
    }

    @Test
    fun matricesPreparedForOtherFramesAreIgnored() {
        val mismatched = prepared(frames.take(2))
        compose.setContent { QrCodeView(frames = frames, initial = mismatched) }
        compose.waitUntil(5_000) { plate.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription) }
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("1/3")
    }

    @Test
    fun aOneFrameCodeNeverKeepsTheScreenOnAndLeavesBrightnessAlone() {
        // QA audit A02: iOS never disables its idle timer, so auto-lock conceals an unattended code (the activity
        // pauses) and starts the App Lock clock. The recovery-phrase QR is one frame (RecoveryQrSection).
        var visible by mutableStateOf(true)
        val single = listOf("UR:BYTES/ONE-FRAME")
        compose.setContent { if (visible) QrCodeView(frames = single) }
        compose.waitUntil(5_000) { plate.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription) }
        val window = compose.activity.window
        assertEquals(0, window.attributes.flags and FLAG_KEEP_SCREEN_ON, "Auto-lock stays in charge")
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, window.attributes.screenBrightness)
        visible = false
        compose.waitForIdle()
        assertEquals(0, window.attributes.flags and FLAG_KEEP_SCREEN_ON)
    }

    @Test
    fun anAnimatedCodeKeepsTheScreenOnOnlyWhilePlaying() {
        // ARCHITECTURE §1.12, design-system.md §9.5: keep-screen-on while an animated code plays, "no brightness boost".
        compose.mainClock.autoAdvance = false
        var visible by mutableStateOf(true)
        val matrices = prepared(frames)
        compose.setContent { if (visible) QrCodeView(frames = frames, initial = matrices) }
        settle()
        val window = compose.activity.window
        assertTrue(window.attributes.flags and FLAG_KEEP_SCREEN_ON != 0, "Playing")
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, window.attributes.screenBrightness)
        compose.onNodeWithTag(QrCodeViewTags.PLAY_PAUSE).performClick()
        settle()
        assertEquals(0, window.attributes.flags and FLAG_KEEP_SCREEN_ON, "Paused: auto-lock applies again")
        compose.onNodeWithTag(QrCodeViewTags.PLAY_PAUSE).performClick()
        settle()
        assertTrue(window.attributes.flags and FLAG_KEEP_SCREEN_ON != 0, "Playing again")
        visible = false
        settle()
        assertEquals(0, window.attributes.flags and FLAG_KEEP_SCREEN_ON)
    }

    @Test
    fun anAnimatedCodeUnderAppLockOrTheCaptureCoverDoesNotHoldTheScreenOn() {
        // The window gate is closed while App Lock is up or the screen is recorded or mirrored: the code is covered
        // (still resumed underneath), so the device must be free to sleep and lock (A02).
        compose.mainClock.autoAdvance = false
        var gateOpen by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalWindowGateOpen provides gateOpen) {
                QrCodeView(frames = frames, initial = prepared(frames))
            }
        }
        settle()
        val window = compose.activity.window
        assertTrue(window.attributes.flags and FLAG_KEEP_SCREEN_ON != 0, "Playing in view")
        gateOpen = false
        settle()
        assertEquals(0, window.attributes.flags and FLAG_KEEP_SCREEN_ON, "Covered: auto-lock applies again")
        gateOpen = true
        settle()
        assertTrue(window.attributes.flags and FLAG_KEEP_SCREEN_ON != 0, "In view again after unlock")
    }

    @Test
    fun anAnimatedCodeKeepsTheScreenOnOnlyOnceItsPartsAreEncoded() {
        var visible by mutableStateOf(true)
        compose.setContent { if (visible) QrCodeView(frames = frames) }
        compose.waitUntil(5_000) { plate.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription) }
        assertTrue(compose.activity.window.attributes.flags and FLAG_KEEP_SCREEN_ON != 0)
        visible = false
        compose.waitForIdle()
        assertEquals(0, compose.activity.window.attributes.flags and FLAG_KEEP_SCREEN_ON)
    }

    @Test
    fun fullBrightnessIsOptInAndRestoredWhenTheCodeLeaves() {
        var visible by mutableStateOf(true)
        val single = listOf("UR:BYTES/BRIGHT")
        val matrices = prepared(single)
        compose.setContent { if (visible) QrCodeView(frames = single, initial = matrices, maxBrightness = true) }
        compose.waitForIdle()
        val window = compose.activity.window
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL, window.attributes.screenBrightness)
        assertEquals(0, window.attributes.flags and FLAG_KEEP_SCREEN_ON, "Brightness does not keep a one-frame code on")
        visible = false
        compose.waitForIdle()
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, window.attributes.screenBrightness)
    }

    @Test
    fun pausingABrightAnimatedCodeKeepsItsBrightness() {
        compose.mainClock.autoAdvance = false
        compose.setContent { QrCodeView(frames = frames, initial = prepared(frames), maxBrightness = true) }
        settle()
        val window = compose.activity.window
        compose.onNodeWithTag(QrCodeViewTags.PLAY_PAUSE).performClick()
        settle()
        assertEquals(0, window.attributes.flags and FLAG_KEEP_SCREEN_ON)
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL, window.attributes.screenBrightness)
    }

    @Test
    fun insideADialogTheDialogsOwnWindowStaysOn() {
        // A sheet or a full-screen dialog is a separate window above the activity (wallets-pairing.md §13.9 item 8).
        var visible by mutableStateOf(true)
        compose.setContent {
            if (visible) Dialog(onDismissRequest = {}) { QrCodeView(frames = frames, initial = prepared(frames)) }
        }
        compose.waitForIdle()
        val dialogWindow = assertNotNull(ShadowDialog.getLatestDialog()?.window)
        assertTrue(dialogWindow.attributes.flags and FLAG_KEEP_SCREEN_ON != 0)
        assertEquals(0, compose.activity.window.attributes.flags and FLAG_KEEP_SCREEN_ON)
        visible = false
        compose.waitForIdle()
        assertEquals(0, dialogWindow.attributes.flags and FLAG_KEEP_SCREEN_ON)
    }

    @Test
    fun anAnimatedCodeShownAgainAfterATabSwitchIsNotEncodedAgainAndContinues() {
        // QA audit A13; iOS keeps QRCodeView's @State across TabView switches (`guard renderedFrames != frames`).
        val entry = ScreenEntry()
        var shown by mutableStateOf(true)
        val encodesBefore = QrFrames.renderCalls.get()
        compose.setContent { OnScreen(entry) { if (shown) QrCodeView(frames = frames) } }
        compose.waitUntil(5_000) { plate.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeUntil(timeoutMillis = 1_000) { counter() == "2/3" }

        // Another tab: navigation moves the entry below RESUMED, then its composition goes.
        compose.runOnIdle { entry.state = Lifecycle.State.CREATED }
        shown = false
        settle()
        // Back to the tab.
        shown = true
        compose.runOnIdle { entry.state = Lifecycle.State.RESUMED }
        settle()

        plate.assert(hasDescription) // no spinner
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("2/3")
        assertEquals(encodesBefore + 1, QrFrames.renderCalls.get(), "Encoded once")
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("3/3")
    }

    @Test
    fun aPreparedAnimatedCodeContinuesAtItsPartAfterATabSwitch() {
        // Connect's prefetched Wallet Level code (`initial`): nothing to encode, the part it reached is kept.
        compose.mainClock.autoAdvance = false
        val entry = ScreenEntry()
        var shown by mutableStateOf(true)
        val matrices = prepared(frames)
        compose.setContent { OnScreen(entry) { if (shown) QrCodeView(frames = frames, initial = matrices) } }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("2/3")

        compose.runOnIdle { entry.state = Lifecycle.State.CREATED }
        shown = false
        settle()
        shown = true
        compose.runOnIdle { entry.state = Lifecycle.State.RESUMED }
        settle()

        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("2/3")
    }

    @Test
    fun anAnimatedCodeThatLeavesAResumedScreenIsForgotten() {
        // Done, a new request, a closed sheet: the code went away, its frames are not kept for later.
        val entry = ScreenEntry()
        var shown by mutableStateOf(true)
        val encodesBefore = QrFrames.renderCalls.get()
        compose.setContent { OnScreen(entry) { if (shown) QrCodeView(frames = frames) } }
        compose.waitUntil(5_000) { plate.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeUntil(timeoutMillis = 1_000) { counter() == "2/3" }

        shown = false
        settle()
        shown = true
        settle()

        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("1/3")
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { plate.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription) }
        assertEquals(encodesBefore + 2, QrFrames.renderCalls.get(), "Encoded again")
    }

    @Test
    fun aOneFrameCodeIsNeverKeptAfterItLeaves() {
        // A one-frame code may be the recovery phrase: nothing of it outlives its composition.
        val entry = ScreenEntry()
        var shown by mutableStateOf(true)
        val single = listOf("UR:BYTES/NOT-KEPT")
        val encodesBefore = QrFrames.renderCalls.get()
        compose.setContent { OnScreen(entry) { if (shown) QrCodeView(frames = single) } }
        compose.waitUntil(5_000) { plate.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription) }

        compose.runOnIdle { entry.state = Lifecycle.State.CREATED }
        shown = false
        compose.waitForIdle()
        shown = true
        compose.runOnIdle { entry.state = Lifecycle.State.RESUMED }
        compose.waitUntil(5_000) { plate.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription) }
        assertEquals(encodesBefore + 2, QrFrames.renderCalls.get(), "Encoded again: not kept")
    }

    @Test
    fun anAnimatedCodeAtATabRootSurvivesTheRealTabSwitch() {
        // A13 through navigation-compose itself, with the shell's tab switch (ShellNavigator.showTab): the Scan tab's
        // entry is popped with saveState, so its composition goes while its store stays; restoreState brings it back.
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            Tabs(nav) { QrCodeView(frames = frames) }
        }
        compose.runOnIdle { nav.showTab(SCAN_TAB) }
        awaitEncodedPlate()
        val encodes = QrFrames.renderCalls.get()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeUntil(timeoutMillis = 1_000) { counter() == "2/3" }

        compose.runOnIdle { nav.showTab(WALLETS_TAB) }
        // The Scan tab's composition goes, as in the app (the entry is below RESUMED from the switch on: no part runs).
        compose.mainClock.advanceTimeUntil(timeoutMillis = 2_000) { plateCount() == 0 }
        compose.runOnIdle { nav.showTab(SCAN_TAB) }
        // Parts run again only once the entry is resumed, a full interval after its content is back.
        compose.mainClock.advanceTimeUntil(timeoutMillis = 2_000) { plateCount() == 1 }

        plate.assert(hasDescription) // no spinner
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("2/3")
        assertEquals(encodes, QrFrames.renderCalls.get(), "Not encoded again")
        // It plays on from there once the entry is resumed: the next part is 3/3.
        compose.mainClock.advanceTimeUntil(timeoutMillis = 2_000) { counter() != "2/3" }
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("3/3")
    }

    @Test
    fun aPoppedScreenTakesItsCodesWithIt() {
        // Back from a pushed page clears its entry's store: nothing of its code is kept for a later visit.
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            Tabs(nav) { QrCodeView(frames = frames) }
        }
        compose.runOnIdle { nav.navigate(PUSHED) }
        awaitEncodedPlate()
        val encodes = QrFrames.renderCalls.get()

        compose.runOnIdle { nav.popBackStack() }
        compose.waitUntil(5_000) { plateCount() == 0 }
        compose.runOnIdle { nav.navigate(PUSHED) }
        awaitEncodedPlate()
        assertEquals(encodes + 1, QrFrames.renderCalls.get(), "A new visit encodes again")
    }

    /** The shell in miniature (MainScaffold): Wallets is the start graph, the code sits at the Scan tab's root. */
    @Composable
    private fun Tabs(nav: NavHostController, code: @Composable () -> Unit) {
        NavHost(
            navController = nav,
            startDestination = WALLETS_TAB,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            navigation(startDestination = "wallets", route = WALLETS_TAB) {
                composable("wallets") { Text("Wallets") }
                composable(PUSHED) { code() }
            }
            navigation(startDestination = "scan", route = SCAN_TAB) {
                composable("scan") { code() }
            }
        }
    }

    /** `ShellNavigator.showTab`. */
    private fun NavHostController.showTab(tab: String) = navigate(tab) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    private fun plateCount(): Int = compose.onAllNodesWithTag(QrCodeViewTags.PLATE).fetchSemanticsNodes().size

    private fun awaitEncodedPlate() {
        compose.waitUntil(5_000) { plateCount() == 1 }
        compose.waitUntil(5_000) { plate.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription) }
    }

    /** A navigation entry's owners: the lifecycle the NavHost drives and the store that outlives a tab switch. */
    private class ScreenEntry : LifecycleOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
        var state: Lifecycle.State
            get() = registry.currentState
            set(value) {
                registry.currentState = value
            }
    }

    @Composable
    private fun OnScreen(entry: ScreenEntry, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalLifecycleOwner provides entry,
            LocalViewModelStoreOwner provides entry,
            content = content,
        )
    }

    @Test
    fun aZeroIntervalStillYieldsToTheMainThread() {
        // `delay(0)` never suspends: without a floor the part loop would spin the main thread forever.
        compose.mainClock.autoAdvance = false
        compose.setContent { QrCodeView(frames = frames, initial = prepared(frames), intervalMillis = 0) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).assertTextEquals("1/3")
        // The parts still advance, at most once per display frame.
        compose.mainClock.advanceTimeUntil(timeoutMillis = 200) { counter() != "1/3" }
    }

    private companion object {
        const val WALLETS_TAB = "walletsTab"
        const val SCAN_TAB = "scanTab"
        const val PUSHED = "pushed"
    }

    private fun counter(): String =
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
}
