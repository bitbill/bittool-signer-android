package com.bittool.signer.core.security.lifecycle

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.bittool.signer.core.common.log.Log
import java.util.function.Consumer

/**
 * The capture callbacks of one started activity (app-shell.md §15.8), registered in `onStart` and closed in `onStop`:
 *
 * - API 35+ `WindowManager.addScreenRecordingCallback` (permission `DETECT_SCREEN_RECORDING`, declared by this
 *   module): [onRecording] gets the initial state at once and every change after it — the iOS `UIScreen.isCaptured`.
 * - API 34+ `Activity.registerScreenCaptureCallback` (permission `DETECT_SCREEN_CAPTURE`): [onScreenshot] after a
 *   user-initiated screenshot — the iOS `userDidTakeScreenshotNotification`. With `FLAG_SECURE` the image is blank and
 *   the callback may not fire at all; it is kept as defence in depth (debug captures, OEM behaviour).
 *
 * A platform refusal (a missing permission, an OEM build without the service) leaves that signal off; the activity's
 * `FLAG_SECURE` still blanks recordings and screenshots.
 */
internal class CaptureCallbacks private constructor(private val unregister: List<() -> Unit>) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        unregister.forEach { runCatching(it) }
    }

    companion object {
        private const val TAG = "CaptureCallbacks"

        fun register(activity: Activity, onRecording: (Boolean) -> Unit, onScreenshot: () -> Unit): CaptureCallbacks {
            val actions = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ScreenshotApi34.register(activity, onScreenshot)?.let(::add)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    RecordingApi35.register(activity, onRecording)?.let(::add)
                }
            }
            return CaptureCallbacks(actions)
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private object ScreenshotApi34 {
            fun register(activity: Activity, onScreenshot: () -> Unit): (() -> Unit)? {
                val callback = Activity.ScreenCaptureCallback { onScreenshot() }
                return try {
                    activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
                    val unregister: () -> Unit = { activity.unregisterScreenCaptureCallback(callback) }
                    unregister
                } catch (e: RuntimeException) {
                    Log.w(TAG, e) { "Screenshot callback unavailable" }
                    null
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
        private object RecordingApi35 {
            fun register(activity: Activity, onRecording: (Boolean) -> Unit): (() -> Unit)? {
                val windowManager = activity.windowManager
                val callback = Consumer<Int> { state -> onRecording(state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) }
                return try {
                    val initial = windowManager.addScreenRecordingCallback(activity.mainExecutor, callback)
                    onRecording(initial == WindowManager.SCREEN_RECORDING_STATE_VISIBLE)
                    val unregister: () -> Unit = { windowManager.removeScreenRecordingCallback(callback) }
                    unregister
                } catch (e: RuntimeException) {
                    Log.w(TAG, e) { "Screen-recording callback unavailable" }
                    null
                }
            }
        }
    }
}
