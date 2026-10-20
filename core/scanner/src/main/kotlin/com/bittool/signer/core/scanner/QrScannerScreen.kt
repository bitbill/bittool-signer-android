package com.bittool.signer.core.scanner

import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bittool.signer.core.model.scanning.QrScanFrameGate
import com.bittool.signer.core.model.scanning.QrScanPurpose
import com.bittool.signer.core.scanner.camera.CameraIssue
import com.bittool.signer.core.scanner.camera.QrCameraPreview
import com.bittool.signer.core.scanner.camera.QrCameraState
import com.bittool.signer.core.scanner.internal.ScannerPalette
import com.bittool.signer.core.scanner.internal.hostWindow
import com.bittool.signer.core.scanner.scan.QrScannerContent
import com.bittool.signer.core.scanner.scan.ScannerChrome
import com.bittool.signer.core.l10n.R as L10n

/**
 * The full-screen QR scanner, shared by signing requests and recovery-phrase import: port of iOS `QRScannerView`
 * (`Features/Sign/QRScannerView.swift:9-127`; scan-sign.md §6, §23.3–§23.5; design-system.md §5.23).
 *
 * - Always dark, in this subtree only (never the app's night mode), black edge to edge, status bar hidden while
 *   shown and light system-bar icons; everything is restored when it leaves.
 * - Asks for the camera on appearance when needed; "Camera Access Needed" with "Open Settings" when denied; a grant
 *   made in Settings starts the camera on return ([rememberCameraPermissionState]).
 * - The camera runs only while the screen is resumed and is released on every pause; the torch goes off with it.
 * - Every frame's QR values go through [QrScanFrameGate] in frame order on the main thread, with
 *   `SystemClock.elapsedRealtime()`: an accepted text reaches [onScan] **unchanged** (and clears the camera message);
 *   a rejection shows the [purpose]'s message under the hint; an identical outcome within 1 s is dropped. The gate
 *   starts afresh each time the camera does (iOS creates it with the camera coordinator).
 * - While [error] is showing, frames are not decoded at all (iOS ignores them in the session anyway); [onReset]
 *   ("Scan Again") is the way back. A new error plays the reject haptic.
 * - System Back and the Close button call [onClose] (iOS dismiss and the accessibility escape action).
 *
 * @param title resolved title ("Scan Request", "Scan Recovery QR").
 * @param hint resolved guidance shown when there is no [status].
 * @param progress reading progress 0…1 of an animated code, or null (recovery import); drawn as a ring and, while
 *   `0 < progress < 1`, as a bar with a percentage.
 * @param status session status shown verbatim instead of the hint ("Reading request…", "Keep the animated code in
 *   view · 4 parts"), resolved by the caller.
 * @param error a session or protocol error, shown verbatim in orange with "Scan Again".
 */
@Composable
fun QrScannerScreen(
    title: String,
    hint: String,
    purpose: QrScanPurpose,
    progress: Float?,
    status: String?,
    error: String?,
    onReset: () -> Unit,
    onClose: () -> Unit,
    onScan: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    QrScannerScreen(
        title = title,
        hint = hint,
        purpose = purpose,
        progress = progress,
        status = status,
        error = error,
        onReset = onReset,
        onClose = onClose,
        onScan = onScan,
        permission = rememberCameraPermissionState(),
        modifier = modifier,
    )
}

/** [QrScannerScreen] with its permission state supplied (tests drive the system answers through it). */
@Composable
internal fun QrScannerScreen(
    title: String,
    hint: String,
    purpose: QrScanPurpose,
    progress: Float?,
    status: String?,
    error: String?,
    onReset: () -> Unit,
    onClose: () -> Unit,
    onScan: (String) -> Unit,
    permission: CameraPermissionState,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(permission, permission.shouldRequestOnEnter) {
        if (permission.shouldRequestOnEnter) permission.request()
    }

    val cameraState = remember { QrCameraState() }
    val frames = remember { ScanFrames(SystemClock::elapsedRealtime) }
    LifecycleResumeEffect(frames) {
        frames.restart()
        onPauseOrDispose { }
    }
    val latestOnScan by rememberUpdatedState(onScan)
    val onValues: (List<String?>) -> Unit = remember(frames) { { values -> frames.receive(values) { latestOnScan(it) } } }

    BackHandler(onBack = onClose)
    ScannerSystemBars()
    ErrorHaptic(error)

    val cameraMessage = when (val issue = cameraState.issue) {
        null -> frames.issue?.let { stringResource(it.messageRes(purpose)) }
        else -> stringResource(issue.messageRes())
    }
    val animationsEnabled = rememberAnimationsEnabled()
    MaterialTheme(colorScheme = ScannerPalette.ColorScheme) {
        QrScannerContent(
            chrome = ScannerChrome(
                title = title,
                hint = hint,
                progress = progress,
                status = status,
                error = error,
                permission = permission.status,
                cameraMessage = cameraMessage,
                torchAvailable = cameraState.torchAvailable,
                torchOn = cameraState.torchOn,
                animationsEnabled = animationsEnabled,
            ),
            onToggleTorch = cameraState::toggleTorch,
            onOpenSettings = permission::openSettings,
            onReset = onReset,
            onClose = onClose,
            modifier = modifier,
            camera = {
                QrCameraPreview(
                    state = cameraState,
                    analysisEnabled = error == null,
                    onValues = onValues,
                )
            },
        )
    }
}

/**
 * The scanner's frame handling on the main thread (iOS `metadataOutput` + the `onScan` wrapper,
 * `QRScannerView.swift:31, :364-370`): each value of a frame, in order, goes through the current [QrScanFrameGate]
 * with the monotonic [clock]; accepted text is delivered unchanged and clears [issue], a rejection sets it, a
 * throttled outcome does nothing. [restart] begins a new gate, as iOS does with every new camera coordinator.
 */
internal class ScanFrames(private val clock: () -> Long) {
    private var gate = QrScanFrameGate()

    /** The latest gate rejection, shown under the hint until a frame is accepted. */
    var issue: QrScanFrameGate.Issue? by mutableStateOf(null)
        private set

    fun restart() {
        gate = QrScanFrameGate()
    }

    fun receive(values: List<String?>, onAccepted: (String) -> Unit) {
        for (value in values) {
            when (val outcome = gate.receive(value, clock())) {
                is QrScanFrameGate.Outcome.Accepted -> {
                    issue = null
                    onAccepted(outcome.text)
                }
                is QrScanFrameGate.Outcome.Rejected -> issue = outcome.issue
                QrScanFrameGate.Outcome.Throttled -> Unit
            }
        }
    }
}

/** The `strings_scanner.xml` text of a gate rejection: the exact iOS English of [QrScanFrameGate.Issue.english]. */
@StringRes
internal fun QrScanFrameGate.Issue.messageRes(purpose: QrScanPurpose): Int = when (purpose) {
    QrScanPurpose.SigningRequest -> when (this) {
        QrScanFrameGate.Issue.NonText -> L10n.string.scanner_gate_non_text_signing
        QrScanFrameGate.Issue.Empty -> L10n.string.scanner_gate_empty_signing
        QrScanFrameGate.Issue.TooLarge -> L10n.string.scanner_gate_too_large_signing
    }
    QrScanPurpose.RecoveryPhrase -> when (this) {
        QrScanFrameGate.Issue.NonText -> L10n.string.scanner_gate_non_text_recovery
        QrScanFrameGate.Issue.Empty -> L10n.string.scanner_gate_empty_recovery
        QrScanFrameGate.Issue.TooLarge -> L10n.string.scanner_gate_too_large_recovery
    }
}

/** iOS camera-setup messages (localised catalogue strings, `QRScannerView.swift:288-299`; scan-sign.md §23.3). */
@StringRes
internal fun CameraIssue.messageRes(): Int = when (this) {
    CameraIssue.NoCamera -> L10n.string.no_camera_is_available_import_the_request_as_a_file
    CameraIssue.Unavailable -> L10n.string.the_camera_is_unavailable
    CameraIssue.ScanningUnavailable -> L10n.string.qr_scanning_is_unavailable
}

/** `.sensoryFeedback(.error, trigger: error) { _, new in new != nil }`: a reject haptic when a new error appears. */
@Composable
private fun ErrorHaptic(error: String?) {
    val view = LocalView.current
    var previous by remember { mutableStateOf(error) }
    LaunchedEffect(error) {
        if (error != null && error != previous) {
            val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
            view.performHapticFeedback(feedback)
        }
        previous = error
    }
}

/**
 * Hides the status bar (iOS `.statusBarHidden()`) and makes the system-bar icons light on the scanner's black page,
 * on the window that hosts the scanner (activity or full-screen dialog). Restored on exit.
 */
@Composable
private fun ScannerSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.hostWindow() ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val statusBarWasVisible = ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val lightStatusBars = controller.isAppearanceLightStatusBars
        val lightNavigationBars = controller.isAppearanceLightNavigationBars
        val behavior = controller.systemBarsBehavior
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        onDispose {
            if (statusBarWasVisible) controller.show(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = behavior
            controller.isAppearanceLightStatusBars = lightStatusBars
            controller.isAppearanceLightNavigationBars = lightNavigationBars
        }
    }
}

/** False when the user removed animations (developer option / accessibility "Remove animations"). */
@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}
