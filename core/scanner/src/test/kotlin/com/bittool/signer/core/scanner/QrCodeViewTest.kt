package com.bittool.signer.core.scanner

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import com.bittool.signer.core.model.QrMatrix
import com.bittool.signer.core.scanner.testing.urFrame
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** iOS `QRCodeView` behaviour (`Design/QRCodeView.swift:29-111`; scan-sign.md §15.1) plus the Android window effects. */
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
    fun aShownCodeKeepsTheScreenOnButLeavesBrightnessAloneByDefault() {
        // design-system.md §9.5: keep-screen-on is Android polish, "no brightness boost — iOS has none". A brighter
        // screen would also make a recovery-phrase code easier to photograph over a shoulder.
        var visible by mutableStateOf(true)
        val single = listOf("UR:BYTES/SHOWN")
        compose.setContent { if (visible) QrCodeView(frames = single, initial = prepared(single)) }
        compose.waitForIdle()
        val window = compose.activity.window
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, window.attributes.screenBrightness)
        visible = false
        compose.waitForIdle()
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test
    fun fullBrightnessIsOptInAndRestoredWhenTheCodeLeaves() {
        var visible by mutableStateOf(true)
        val single = listOf("UR:BYTES/BRIGHT")
        val matrices = prepared(single)
        compose.setContent { if (visible) QrCodeView(frames = single, initial = matrices, maxBrightness = true) }
        compose.waitForIdle()
        val window = compose.activity.window
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL, window.attributes.screenBrightness)
        visible = false
        compose.waitForIdle()
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, window.attributes.screenBrightness)
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

    private fun counter(): String =
        compose.onNodeWithTag(QrCodeViewTags.COUNTER).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
}
