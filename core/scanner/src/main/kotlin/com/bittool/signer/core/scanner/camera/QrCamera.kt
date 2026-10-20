package com.bittool.signer.core.scanner.camera

import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.view.View
import androidx.annotation.MainThread
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import com.bittool.signer.core.common.log.Log
import com.bittool.signer.core.scanner.analysis.QrFrameAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Camera problems shown in the scanner's status panel (scan-sign.md §6.7, §23.3 table). */
internal enum class CameraIssue {
    /** No camera hardware: "No camera is available. Import the request as a file instead." */
    NoCamera,

    /** CameraX could not start or bind, or the camera reports an error (in use, disabled by policy, fatal…). */
    Unavailable,

    /** The camera runs but the analysis stream could not be bound next to the preview. */
    ScanningUnavailable,
}

/** Observable camera facts for the scanner chrome; written by [QrCameraSession] on the main thread. */
@Stable
internal class QrCameraState {
    var torchAvailable: Boolean by mutableStateOf(false)
        internal set
    var torchOn: Boolean by mutableStateOf(false)
        internal set
    var issue: CameraIssue? by mutableStateOf(null)
        internal set

    internal var session: QrCameraSession? = null

    /** iOS `torchOn.toggle()` (`QRScannerView.swift:73`); a no-op without a flash unit or camera. */
    fun toggleTorch() {
        if (torchAvailable) session?.setTorch(!torchOn)
    }

    /** Tap-to-focus at a point of the preview, in its pixels; false when the camera cannot meter there. */
    fun focus(x: Float, y: Float): Boolean = session?.focus(x, y) ?: false
}

/**
 * One camera presentation (port of `CameraPreview.Coordinator`, `QRScannerView.swift:269-355`), created when the
 * preview enters composition and [release]d when it leaves:
 *
 * - back camera, else the front one; none → [CameraIssue.NoCamera];
 * - `Preview` (on a TextureView-backed [PreviewView], `FILL_CENTER` = `resizeAspectFill`) and `ImageAnalysis`
 *   (`YUV_420_888`, `STRATEGY_KEEP_ONLY_LATEST`), both at 1920 × 1080 16:9 with "closest lower, then higher"
 *   fallback, the equivalent of iOS's `hd1920x1080` preset;
 * - bound to a [ResumedLifecycleOwner], so the camera device runs only while the host is resumed (iOS: only while the
 *   scene is active) and is closed, torch included, on every pause;
 * - continuous autofocus (CameraX default) plus tap-to-focus/metering that auto-cancels after 3 s;
 * - torch availability from `hasFlashUnit()`, its state from `torchState`; turned off on pause and on release;
 * - errors map to [CameraIssue]; a camera that recovers (back to OPEN without error) clears `Unavailable`.
 *
 * Decoded values reach [onValues] on the main thread, never after [release].
 */
@MainThread
internal class QrCameraSession(
    private val context: Context,
    private val hostLifecycle: Lifecycle,
    private val previewView: PreviewView,
    private val state: QrCameraState,
    private val onValues: (List<String?>) -> Unit,
) {
    private val owner = ResumedLifecycleOwner(hostLifecycle)
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, ANALYSIS_THREAD_NAME)
    }
    private val scope = MainScope()

    /** Written on the main thread, also read by the analysis thread to skip work after [release]. */
    @Volatile
    private var released = false
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var useCases: Array<UseCase> = emptyArray()

    private val torchObserver = Observer<Int> { state.torchOn = it == TorchState.ON }
    private val cameraStateObserver = Observer<CameraState> { onCameraState(it) }

    /** iOS forces the torch off whenever the scene is not active (`QRScannerView.swift:58`). */
    private val pauseObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE && state.torchOn) camera?.cameraControl?.enableTorch(false)
    }

    fun start() {
        state.session = this
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            state.issue = CameraIssue.NoCamera
            return
        }
        hostLifecycle.addObserver(pauseObserver)
        scope.launch {
            val cameraProvider = try {
                ProcessCameraProvider.awaitInstance(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "CameraX initialisation failed" }
                state.issue = CameraIssue.Unavailable
                return@launch
            }
            if (!released) bind(cameraProvider)
        }
    }

    private fun bind(cameraProvider: ProcessCameraProvider) {
        provider = cameraProvider
        val selector = try {
            when {
                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                else -> null
            }
        } catch (e: CameraInfoUnavailableException) {
            Log.w(TAG, e) { "camera enumeration failed" }
            state.issue = CameraIssue.Unavailable
            return
        }
        if (selector == null) {
            state.issue = CameraIssue.NoCamera
            return
        }
        val resolution = resolutionSelector()
        val preview = Preview.Builder().setResolutionSelector(resolution).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val bound: Camera = try {
            cameraProvider.bindToLifecycle(owner, selector, preview, imageAnalysis).also {
                useCases = arrayOf(preview, imageAnalysis)
                analysis = imageAnalysis
            }
        } catch (e: IllegalArgumentException) {
            // The device cannot stream preview and analysis together: keep the preview, report that scanning is off.
            Log.w(TAG, e) { "preview + analysis cannot be bound" }
            try {
                cameraProvider.bindToLifecycle(owner, selector, preview).also {
                    useCases = arrayOf(preview)
                    state.issue = CameraIssue.ScanningUnavailable
                }
            } catch (e2: RuntimeException) {
                Log.w(TAG, e2) { "preview cannot be bound" }
                state.issue = CameraIssue.Unavailable
                return
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, e) { "camera binding failed" }
            state.issue = CameraIssue.Unavailable
            return
        }
        camera = bound
        analysis?.setAnalyzer(
            analysisExecutor,
            QrFrameAnalyzer(
                onValues = { values -> mainExecutor.execute { if (!released) onValues(values) } },
                enabled = { !released },
            ),
        )
        state.torchAvailable = bound.cameraInfo.hasFlashUnit()
        bound.cameraInfo.torchState.observeForever(torchObserver)
        bound.cameraInfo.cameraState.observeForever(cameraStateObserver)
    }

    private fun onCameraState(cameraState: CameraState) {
        when {
            cameraState.error != null -> {
                Log.w(TAG) { "camera error ${cameraState.error?.code}" }
                if (state.issue != CameraIssue.NoCamera) state.issue = CameraIssue.Unavailable
            }
            cameraState.type == CameraState.Type.OPEN && state.issue == CameraIssue.Unavailable -> {
                state.issue = if (analysis == null) CameraIssue.ScanningUnavailable else null
            }
        }
    }

    fun setTorch(on: Boolean) {
        val cam = camera ?: return
        if (released || !cam.cameraInfo.hasFlashUnit()) return
        cam.cameraControl.enableTorch(on)
    }

    fun focus(x: Float, y: Float): Boolean {
        val cam = camera ?: return false
        if (released || previewView.width == 0 || previewView.height == 0) return false
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
            .build()
        if (!cam.cameraInfo.isFocusMeteringSupported(action)) return false
        cam.cameraControl.startFocusAndMetering(action)
        return true
    }

    /** iOS `Coordinator.stop()` (`QRScannerView.swift:346-355`): callbacks off, torch off, camera released. */
    fun release() {
        if (released) return
        released = true
        scope.cancel()
        hostLifecycle.removeObserver(pauseObserver)
        analysis?.clearAnalyzer()
        camera?.let { cam ->
            if (state.torchOn) cam.cameraControl.enableTorch(false)
            cam.cameraInfo.torchState.removeObserver(torchObserver)
            cam.cameraInfo.cameraState.removeObserver(cameraStateObserver)
        }
        if (useCases.isNotEmpty()) provider?.unbind(*useCases)
        owner.destroy()
        analysisExecutor.shutdown()
        if (state.session === this) state.session = null
        state.torchAvailable = false
        state.torchOn = false
        camera = null
        analysis = null
    }

    companion object {
        private const val TAG = "QrCamera"
        private const val ANALYSIS_THREAD_NAME = "bittool-signer-qr"
        private const val FOCUS_AUTO_CANCEL_SECONDS = 3L

        /** 1080p 16:9, preferring the closest lower size when 1080p is not offered (iOS `hd1920x1080`). */
        fun resolutionSelector(): ResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()

        /** A TextureView-backed preview that behaves inside Compose transitions; hidden from accessibility. */
        fun previewView(context: Context): PreviewView = PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }
}
