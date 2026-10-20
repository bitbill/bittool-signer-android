package com.bittool.signer.core.security.testing

import androidx.fragment.app.FragmentActivity
import com.bittool.signer.core.security.AppLifecycleMonitor
import com.bittool.signer.core.security.LifecycleEvent
import com.bittool.signer.core.security.PrivacySignal
import com.bittool.signer.core.security.PrivacySignalCenter
import com.bittool.signer.core.security.PrivacySignalDispatcher
import com.bittool.signer.core.security.ScenePhase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A drivable [AppLifecycleMonitor] for JVM tests of features (phase, capture and screenshot events). Each driver call
 * updates [phase] / [isCaptured] first, then posts to [privacySignals] (synchronously, like the real monitor), then
 * emits on [events] / [screenshots]. Pair it with [FakeRecoverySessionFactory] to get real recovery-session state
 * machines driven by the same events. Not used by production code.
 *
 * Starts [ScenePhase.Active] (the screen under test is showing) unless told otherwise.
 */
class FakeAppLifecycleMonitor(
    initialPhase: ScenePhase = ScenePhase.Active,
    captured: Boolean = false,
) : AppLifecycleMonitor {

    private val signals = PrivacySignalDispatcher()

    /** The synchronous signal center the recovery sessions observe. */
    val privacySignals: PrivacySignalCenter get() = signals

    /** Live observers of [privacySignals]. */
    val privacyObserverCount: Int get() = signals.observerCount

    private val _phase = MutableStateFlow(initialPhase)
    override val phase: StateFlow<ScenePhase> = _phase.asStateFlow()

    private val _events = MutableSharedFlow<LifecycleEvent>(extraBufferCapacity = BUFFER)
    override val events: SharedFlow<LifecycleEvent> = _events.asSharedFlow()

    private val _isCaptured = MutableStateFlow(captured)
    override val isCaptured: StateFlow<Boolean> = _isCaptured.asStateFlow()

    private val _screenshots = MutableSharedFlow<Unit>(extraBufferCapacity = BUFFER)
    override val screenshots: SharedFlow<Unit> = _screenshots.asSharedFlow()

    override var foregroundActivity: FragmentActivity? = null

    /** Every activity passed to [attach]. */
    val attached: MutableList<FragmentActivity> = mutableListOf()

    override fun attach(activity: FragmentActivity) {
        attached += activity
    }

    /** `onStart` after a background stay: Inactive + WillEnterForeground. */
    fun enterForeground() = transition(ScenePhase.Inactive, LifecycleEvent.WillEnterForeground, PrivacySignal.WillEnterForeground)

    /** `onResume`: Active + DidBecomeActive. */
    fun resume() = transition(ScenePhase.Active, LifecycleEvent.DidBecomeActive, PrivacySignal.DidBecomeActive)

    /** `onPause`: Inactive + WillResignActive. */
    fun pause() = transition(ScenePhase.Inactive, LifecycleEvent.WillResignActive, PrivacySignal.WillResignActive)

    /** `onStop` (not a configuration change): Background + DidEnterBackground. */
    fun enterBackground() = transition(ScenePhase.Background, LifecycleEvent.DidEnterBackground, PrivacySignal.DidEnterBackground)

    /** Pause then stop, as Home does. */
    fun moveToBackground() {
        if (_phase.value == ScenePhase.Active) pause()
        enterBackground()
    }

    /** Start then resume, as returning to the app does. */
    fun returnToForeground() {
        if (_phase.value == ScenePhase.Background) enterForeground()
        resume()
    }

    /** Screen recording / mirroring started or stopped (posts CaptureChanged only on a change). */
    fun setCaptured(captured: Boolean) {
        if (_isCaptured.value == captured) return
        _isCaptured.value = captured
        signals.post(PrivacySignal.CaptureChanged)
    }

    /** A user screenshot. */
    fun takeScreenshot() {
        signals.post(PrivacySignal.Screenshot)
        _screenshots.tryEmit(Unit)
    }

    private fun transition(phase: ScenePhase, event: LifecycleEvent, signal: PrivacySignal) {
        _phase.value = phase
        signals.post(signal)
        _events.tryEmit(event)
    }

    private companion object {
        const val BUFFER = 64
    }
}
