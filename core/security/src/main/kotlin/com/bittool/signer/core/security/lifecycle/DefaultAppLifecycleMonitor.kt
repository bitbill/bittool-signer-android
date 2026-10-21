package com.bittool.signer.core.security.lifecycle

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bittool.signer.core.security.AppLifecycleMonitor
import com.bittool.signer.core.security.LifecycleEvent
import com.bittool.signer.core.security.PrivacySignal
import com.bittool.signer.core.security.PrivacySignalCenter
import com.bittool.signer.core.security.PrivacySignalDispatcher
import com.bittool.signer.core.security.ScenePhase
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single source of scene-phase, capture and screenshot signals (app-shell.md §10, §15.4, §15.8), fed by the
 * activity `MainActivity` attaches in `onCreate`:
 *
 * | Activity callback | [phase] | [events] / [privacySignals] |
 * |---|---|---|
 * | `onStart` after a stay in the background (or the cold start) | Inactive | WillEnterForeground |
 * | `onResume` | Active | DidBecomeActive |
 * | `onPause` | Inactive | WillResignActive |
 * | `onStop`, not changing configurations | Background | DidEnterBackground |
 * | `onStop` while changing configurations (per-app language, …) | stays Inactive | nothing: not a background visit |
 *
 * The background edge comes from the activity, never from `ProcessLifecycleOwner`, whose `ON_STOP` is ~700 ms late
 * while iOS revokes at once. Capture, all registered in `onStart` and removed in `onStop`, each initial state applied at
 * once: [isCaptured] = recording (API 35+ `WindowManager.addScreenRecordingCallback`; below 35 `FLAG_SECURE` blanks
 * recordings) **or** mirroring to another display (every API level, [DisplayMirroring]: `FLAG_SECURE` does not blank a
 * secure external display), like iOS `UIScreen.isCaptured` ("recorded, mirrored, AirPlayed or otherwise cloned").
 * Screenshots: API 34+ `Activity.registerScreenCaptureCallback`.
 *
 * Synchronous delivery: [privacySignals] observers (the recovery sessions) run inside the lifecycle callback, so a
 * revealed phrase is concealed before `onPause` returns; [phase] and [isCaptured] are updated before any signal is
 * posted, so a session that asks "may I reveal?" from its observer sees the new state. Main thread only.
 */
@Singleton
internal class DefaultAppLifecycleMonitor @Inject constructor() : AppLifecycleMonitor {

    private val signals = PrivacySignalDispatcher()

    /** The recovery sessions' notification center (iOS `NotificationCenter.default`). */
    val privacySignals: PrivacySignalCenter get() = signals

    private val _phase = MutableStateFlow(ScenePhase.Background)
    override val phase: StateFlow<ScenePhase> = _phase.asStateFlow()

    private val _events = MutableSharedFlow<LifecycleEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: SharedFlow<LifecycleEvent> = _events.asSharedFlow()

    private val _isCaptured = MutableStateFlow(false)
    override val isCaptured: StateFlow<Boolean> = _isCaptured.asStateFlow()

    private val _screenshots = MutableSharedFlow<Unit>(extraBufferCapacity = EVENT_BUFFER)
    override val screenshots: SharedFlow<Unit> = _screenshots.asSharedFlow()

    private var attached: WeakReference<FragmentActivity>? = null
    private var resumed: WeakReference<FragmentActivity>? = null

    /** A cold start is a return from the background (iOS launches into `willEnterForeground`). */
    private var inBackground = true

    override val foregroundActivity: FragmentActivity?
        get() = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    override fun attach(activity: FragmentActivity) {
        if (attached?.get() === activity) return
        attached = WeakReference(activity)
        activity.lifecycle.addObserver(ActivityObserver())
    }

    internal fun onStarted() {
        val returning = inBackground
        inBackground = false
        _phase.value = ScenePhase.Inactive
        if (returning) dispatch(LifecycleEvent.WillEnterForeground)
    }

    internal fun onResumed(activity: FragmentActivity) {
        resumed = WeakReference(activity)
        _phase.value = ScenePhase.Active
        dispatch(LifecycleEvent.DidBecomeActive)
    }

    internal fun onPaused(activity: FragmentActivity) {
        if (resumed?.get() === activity) resumed = null
        _phase.value = ScenePhase.Inactive
        dispatch(LifecycleEvent.WillResignActive)
    }

    internal fun onStopped(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        inBackground = true
        _phase.value = ScenePhase.Background
        dispatch(LifecycleEvent.DidEnterBackground)
    }

    private var recording = false
    private var mirrored = false

    /** The screen-recording callback: VISIBLE ↔ NOT_VISIBLE. */
    internal fun onRecordingChanged(recording: Boolean) {
        this.recording = recording
        publishCapture()
    }

    /** The display listener: a display that can mirror the screen was connected, switched on, off or removed. */
    internal fun onMirroringChanged(mirrored: Boolean) {
        this.mirrored = mirrored
        publishCapture()
    }

    /** Recorded or mirrored; each change of the combination is an iOS `capturedDidChange`. */
    private fun publishCapture() {
        val captured = recording || mirrored
        if (_isCaptured.value == captured) return
        _isCaptured.value = captured
        signals.post(PrivacySignal.CaptureChanged)
    }

    /** The screenshot callback (after the fact, user-initiated captures only). */
    internal fun onScreenshot() {
        signals.post(PrivacySignal.Screenshot)
        _screenshots.tryEmit(Unit)
    }

    private fun dispatch(event: LifecycleEvent) {
        // Concealment first: the sessions react before any collector of `events` runs.
        signals.post(event.toSignal())
        _events.tryEmit(event)
    }

    private fun isCurrent(owner: LifecycleOwner): Boolean = attached?.get() === owner

    /** Drives the monitor from one activity; an activity replaced by a newer attachment is ignored. */
    private inner class ActivityObserver : DefaultLifecycleObserver {
        private var capture: CaptureCallbacks? = null

        override fun onStart(owner: LifecycleOwner) {
            if (!isCurrent(owner)) return
            onStarted()
            capture?.close()
            capture = CaptureCallbacks.register(
                activity = owner as FragmentActivity,
                onRecording = ::onRecordingChanged,
                onMirroring = ::onMirroringChanged,
                onScreenshot = ::onScreenshot,
            )
        }

        override fun onResume(owner: LifecycleOwner) {
            if (isCurrent(owner)) onResumed(owner as FragmentActivity)
        }

        override fun onPause(owner: LifecycleOwner) {
            if (isCurrent(owner)) onPaused(owner as FragmentActivity)
        }

        override fun onStop(owner: LifecycleOwner) {
            capture?.close()
            capture = null
            if (isCurrent(owner)) onStopped((owner as FragmentActivity).isChangingConfigurations)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            owner.lifecycle.removeObserver(this)
            if (resumed?.get() === owner) resumed = null
            if (attached?.get() === owner) attached = null
        }
    }

    private companion object {
        /** Lifecycle edges are rare; the buffer only keeps a slow collector from losing one. */
        const val EVENT_BUFFER = 64

        fun LifecycleEvent.toSignal(): PrivacySignal = when (this) {
            LifecycleEvent.WillEnterForeground -> PrivacySignal.WillEnterForeground
            LifecycleEvent.DidBecomeActive -> PrivacySignal.DidBecomeActive
            LifecycleEvent.WillResignActive -> PrivacySignal.WillResignActive
            LifecycleEvent.DidEnterBackground -> PrivacySignal.DidEnterBackground
        }
    }
}
