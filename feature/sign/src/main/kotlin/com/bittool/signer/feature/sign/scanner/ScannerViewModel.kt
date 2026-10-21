package com.bittool.signer.feature.sign.scanner

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.common.coroutines.ApplicationScope
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.core.l10n.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the signing scanner shows (iOS `ScanSession`'s published state, `Features/Sign/ScanTab.swift:9-24`).
 *
 * [progress] is the engine's reading progress (0…1, never going back within one session), [received] the number of
 * distinct frames handed in, [processing] whether a frame is with the engine, [error] the session or protocol error
 * (engine or host English, shown verbatim with "Scan Again").
 */
@Immutable
data class ScannerUiState(
    val progress: Double = 0.0,
    val received: Int = 0,
    val processing: Boolean = false,
    val error: UiText? = null,
) {
    /**
     * iOS `ScanSession.status`: "Reading request…" while the first frame is read, "Keep the animated code in view · n
     * parts" while an animated code is incomplete, else null (the scanner shows its hint).
     */
    val status: UiText?
        get() = when {
            processing && received == 1 -> UiText.Resource(R.string.sign_reading_request)
            received > 1 && progress < 1.0 -> UiText.Resource(R.string.sign_keep_animated_code_in_view, listOf(received))
            else -> null
        }
}

/** One-off scanner events. */
sealed interface ScannerEvent {
    /**
     * The store has a review (or a signature), from this camera or from anywhere else: it is already published under
     * the camera, close the camera.
     */
    data object Completed : ScannerEvent
}

/**
 * The signing scanner's session: an exact port of iOS `ScanSession` (`Features/Sign/ScanTab.swift:9-86`, scan-sign.md
 * §7) for one scanner presentation (the ViewModel of `ScannerRoute`).
 *
 * - [start] (on creation and on "Scan Again") resets everything and opens a fresh engine scan session
 *   ([SignerStore.beginScan]); frames that arrive meanwhile are queued and processed once it is ready. A start failure
 *   becomes the scanner error.
 * - [receive] ignores a frame while an error is shown, once a review exists, or when exactly this text was already
 *   handed in during this session; otherwise it queues it.
 * - The queue is drained one frame at a time, in order ([SignerStore.receive]). A result for an older session (token)
 *   is dropped. When the security epoch changed during the call (the app went to the background), the frame goes back
 *   to the front of the queue and is retried with the next new frame. On success the progress only grows; once the
 *   store has a review the session completes ([ScannerEvent.Completed]); otherwise the next frame is processed. A
 *   failure (never a cancellation) shows the error and drops the queue.
 * - A review or a signature that reaches the store from anywhere else (a file import that finishes while the camera
 *   is up) completes the session too. On iOS the camera is a cover of Scan Home (`ScanTab.swift:147`), and `ScanTab`
 *   replaces Home as soon as the store has either (`:96-104`), which dismisses the cover: the camera never stays open
 *   over a review, ignoring every code it is pointed at.
 * - [stop] (Close, Back, completion, or the ViewModel going away) resets the session and ends the engine scan session.
 *
 * Main thread only (`viewModelScope` is `Dispatchers.Main.immediate`).
 */
@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val store: SignerStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val _events = Channel<ScannerEvent>(Channel.BUFFERED)
    val events: Flow<ScannerEvent> = _events.receiveAsFlow()

    private val seen = HashSet<String>()
    private val queue = ArrayDeque<String>()
    private var token = 0L
    private var ready = false
    /** iOS `onComplete != nil`: a frame was handed in during this session (frames queued while starting). */
    private var hasFrames = false
    private var stopped = false

    init {
        start()
        viewModelScope.launch {
            store.state.map(::closesTheCamera).distinctUntilChanged().collect { closes -> if (closes) complete() }
        }
    }

    /** iOS `start(store:)`: a new session, then a fresh engine scan session. Also "Scan Again". */
    fun start() {
        reset()
        stopped = false
        val id = token
        viewModelScope.launch {
            try {
                store.beginScan()
                if (token != id) return@launch
                ready = true
                // Frames seen while the session was starting are processed now.
                if (hasFrames) drain()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (e: Exception) {
                if (token == id) _uiState.update { it.copy(error = e.toUiText()) }
            }
        }
    }

    /**
     * iOS `stop(store:)`: drops the session and ends the engine scan session. Called before the scanner closes (Close,
     * Back, completion); [onCleared] only covers a scanner removed otherwise, so a late clear can never end the session
     * of a scanner opened again meanwhile. The visible state is kept for the closing animation.
     */
    fun stop() {
        if (stopped) return
        stopped = true
        token++
        seen.clear()
        queue.clear()
        ready = false
        hasFrames = false
        // Starts on the calling (main) thread so the end is queued on the engine thread before any later beginScan.
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) { store.endScan() }
    }

    /** iOS `receive(_:store:completed:)`: one accepted QR text, exactly as scanned. */
    fun receive(frame: String) {
        if (stopped || _uiState.value.error != null || store.state.value.review != null || !seen.add(frame)) return
        hasFrames = true
        _uiState.update { it.copy(received = it.received + 1) }
        queue.addLast(frame)
        drain()
    }

    private fun drain() {
        if (!ready || _uiState.value.processing || _uiState.value.error != null || queue.isEmpty()) return
        val frame = queue.removeFirst()
        _uiState.update { it.copy(processing = true) }
        val id = token
        val epoch = store.state.value.securityEpoch
        viewModelScope.launch {
            val outcome: Result<Double> = try {
                Result.success(store.receive(frame))
            } catch (e: CancellationException) {
                // The store reports a changed session as a cancellation; this coroutine itself must not be cancelled.
                currentCoroutineContext().ensureActive()
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (token != id) return@launch
            _uiState.update { it.copy(processing = false) }
            if (epoch != store.state.value.securityEpoch) {
                // Interrupted by backgrounding: retry this part with the next frame.
                queue.addFirst(frame)
                return@launch
            }
            outcome.fold(
                onSuccess = { value ->
                    _uiState.update { it.copy(progress = maxOf(it.progress, value)) }
                    if (closesTheCamera(store.state.value)) complete() else drain()
                },
                onFailure = { e ->
                    if (e is CancellationException) return@fold
                    _uiState.update { it.copy(error = e.toUiText()) }
                    queue.clear()
                },
            )
        }
    }

    /** Closes the camera once per session: the store's review and this scanner's own last frame can both report it. */
    private fun complete() {
        if (stopped) return
        stop()
        _events.trySend(ScannerEvent.Completed)
    }

    /** iOS `reset()`. */
    private fun reset() {
        token++
        seen.clear()
        queue.clear()
        ready = false
        hasFrames = false
        _uiState.value = ScannerUiState()
    }

    override fun onCleared() {
        stop()
    }

    private companion object {
        /** iOS `ScanTab`: Home, and with it the camera, gives way to a signature or a request under review. */
        fun closesTheCamera(state: SignerState): Boolean = state.review != null || state.hasSignedResponse
    }
}
