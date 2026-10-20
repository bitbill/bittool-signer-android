package com.bittool.signer.core.scanner

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bittool.signer.core.scanner.internal.findActivity

/** The runtime CAMERA permission as the scanner sees it (iOS `AVCaptureDevice.authorizationStatus`). */
enum class CameraPermissionStatus {
    /** Granted: the camera may run. */
    Granted,

    /** Not asked yet in this scanner presentation, or the system dialog is showing (iOS `notDetermined`). */
    NotAsked,

    /** Denied, and the system would show its dialog again (`shouldShowRequestPermissionRationale`). */
    Denied,

    /** Denied, and the system no longer shows its dialog ("Don't allow" twice, or a policy): only Settings helps. */
    PermanentlyDenied,
}

/**
 * Runtime CAMERA permission flow of the scanner (scan-sign.md §6.6, §23.5), created by [rememberCameraPermissionState].
 *
 * iOS asks on the scanner's first appearance when the status is `notDetermined`, shows "Camera Access Needed" with
 * "Open Settings" when denied, and re-reads the status whenever the scene becomes active again, so a grant made in
 * Settings starts the camera without reopening the scanner (`QRScannerView.swift:21, :51-59, :87-95`). Android:
 * - [request] launches the system dialog; "asked" is remembered for this presentation (saved state), so a
 *   configuration change never asks twice. Whether the system then shows a dialog is its own decision: a permanent
 *   denial returns at once. (Persisting "asked" across presentations would misread a permission reset from Settings
 *   or `pm revoke` as permanently denied.)
 * - every `ON_RESUME` re-checks and upgrades to [CameraPermissionStatus.Granted]; it never prompts (re-prompting on
 *   resume would stack dialogs: the request itself pauses the activity).
 * - [openSettings] opens the app's system settings page (`ACTION_APPLICATION_DETAILS_SETTINGS`, falling back to the
 *   settings root). The button that calls it keeps the iOS label "Open Settings" in every denied state (DECISIONS §10:
 *   no Android-only "Allow camera" label).
 */
@Stable
class CameraPermissionState internal constructor(
    private val context: Context,
    requestedBefore: Boolean,
) {
    /** True once this presentation launched the system request. */
    internal var requested: Boolean by mutableStateOf(requestedBefore)
        private set

    var status: CameraPermissionStatus by mutableStateOf(
        CameraPermissionFlow.initial(context.hasCameraPermission(), requestedBefore, context.shouldShowCameraRationale()),
    )
        private set

    internal var launcher: ManagedActivityResultLauncher<String, Boolean>? = null

    /** Whether the scanner should ask right away when it appears (iOS `.task`: ask while `notDetermined`). */
    internal val shouldRequestOnEnter: Boolean
        get() = status != CameraPermissionStatus.Granted && !requested

    /** Shows the system permission dialog (or lets the system answer at once when it no longer asks). */
    fun request() {
        val launcher = launcher ?: return
        requested = true
        try {
            launcher.launch(Manifest.permission.CAMERA)
        } catch (e: ActivityNotFoundException) {
            status = CameraPermissionFlow.onResult(granted = false, shouldShowRationale = false)
        }
    }

    /** Opens this app's page in the system settings, where the camera permission can be turned on. */
    fun openSettings() {
        val activity = context.findActivity()
        val starter = activity ?: context
        val newTask = if (activity == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(newTask)
        try {
            starter.startActivity(details)
        } catch (e: ActivityNotFoundException) {
            try {
                starter.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(newTask))
            } catch (e2: ActivityNotFoundException) {
                // No settings app at all (some kiosk builds): nothing else can be offered.
            }
        }
    }

    internal fun onRequestResult(granted: Boolean) {
        status = CameraPermissionFlow.onResult(granted, context.shouldShowCameraRationale())
    }

    internal fun onResume() {
        status = CameraPermissionFlow.onResume(
            current = status,
            granted = context.hasCameraPermission(),
            requestedBefore = requested,
            shouldShowRationale = context.shouldShowCameraRationale(),
        )
    }
}

/**
 * Remembers the scanner's [CameraPermissionState] for this composition (the "asked" flag survives configuration
 * changes) and re-checks the permission on every resume. It does not ask by itself: `QrScannerScreen` calls
 * [CameraPermissionState.request] when it appears.
 */
@Composable
fun rememberCameraPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    val state = rememberSaveable(
        context,
        saver = Saver(save = { it.requested }, restore = { CameraPermissionState(context, requestedBefore = it) }),
    ) { CameraPermissionState(context, requestedBefore = false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state.onRequestResult(granted)
    }
    // A DisposableEffect (not a SideEffect): remember observers run in composition order, so the launcher is in place
    // before any effect composed after this call, such as the scanner's ask-on-appearance, can call request().
    DisposableEffect(state, launcher) {
        state.launcher = launcher
        onDispose { if (state.launcher === launcher) state.launcher = null }
    }
    LifecycleResumeEffect(state) {
        state.onResume()
        onPauseOrDispose { }
    }
    return state
}

/** Pure transitions of the permission flow (unit-tested; the composable feeds it platform facts). */
internal object CameraPermissionFlow {
    /** First composition (or restoration after a configuration change). */
    fun initial(granted: Boolean, requestedBefore: Boolean, shouldShowRationale: Boolean): CameraPermissionStatus = when {
        granted -> CameraPermissionStatus.Granted
        !requestedBefore -> CameraPermissionStatus.NotAsked
        shouldShowRationale -> CameraPermissionStatus.Denied
        else -> CameraPermissionStatus.PermanentlyDenied
    }

    /** The system request returned. */
    fun onResult(granted: Boolean, shouldShowRationale: Boolean): CameraPermissionStatus = when {
        granted -> CameraPermissionStatus.Granted
        shouldShowRationale -> CameraPermissionStatus.Denied
        else -> CameraPermissionStatus.PermanentlyDenied
    }

    /**
     * Back in the foreground (e.g. from Settings): upgrades to granted and never prompts. A grant that disappeared
     * while away (a one-time grant expiring) is re-read like a first composition.
     */
    fun onResume(
        current: CameraPermissionStatus,
        granted: Boolean,
        requestedBefore: Boolean,
        shouldShowRationale: Boolean,
    ): CameraPermissionStatus = when {
        granted -> CameraPermissionStatus.Granted
        current == CameraPermissionStatus.Granted -> initial(granted = false, requestedBefore, shouldShowRationale)
        else -> current
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.shouldShowCameraRationale(): Boolean {
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
}
