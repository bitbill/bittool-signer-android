package com.bittool.signer.core.scanner.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlinx.coroutines.launch

/**
 * The live camera behind the scanner chrome (iOS `CameraPreview`, `QRScannerView.swift:241-267`). Compose it only
 * with the camera permission granted. The camera runs while the host lifecycle is resumed; while it is not, the last
 * frame is hidden (iOS removes the preview when the scene is inactive). Tapping focuses and meters at that point,
 * with a brief ring. Hidden from accessibility.
 *
 * @param onValues the "prefer readable" values of each frame that held QR codes, on the main thread.
 */
@Composable
internal fun QrCameraPreview(
    state: QrCameraState,
    onValues: (List<String?>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hostLifecycle = LocalLifecycleOwner.current.lifecycle
    val latestOnValues by rememberUpdatedState(onValues)
    val previewView = remember(context) { QrCameraSession.previewView(context) }

    DisposableEffect(hostLifecycle, previewView, state) {
        val session = QrCameraSession(context, hostLifecycle, previewView, state, onValues = { latestOnValues(it) })
        session.start()
        onDispose { session.release() }
    }

    val lifecycleState by hostLifecycle.currentStateAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTap by remember { mutableIntStateOf(0) }

    Box(modifier.clearAndSetSemantics { }) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
            update = { it.alpha = if (visible) 1f else 0f },
        )
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(state) {
                    detectTapGestures { offset ->
                        if (state.focus(offset.x, offset.y)) {
                            focusPoint = offset
                            focusTap++
                        }
                    }
                },
        )
        focusPoint?.let { FocusRing(it, focusTap) }
    }
}

/** A ring in the theme's `mint` that settles on the tapped point and fades (~0.8 s). */
@Composable
private fun FocusRing(center: Offset, tap: Int) {
    val scale = remember { Animatable(1.3f) }
    val alpha = remember { Animatable(0f) }
    val mint = SignerTheme.colors.mint
    LaunchedEffect(tap) {
        scale.snapTo(1.3f)
        alpha.snapTo(1f)
        launch { scale.animateTo(1f, tween(220)) }
        alpha.animateTo(0f, tween(durationMillis = 500, delayMillis = 300))
    }
    Canvas(Modifier.fillMaxSize()) {
        if (alpha.value <= 0f) return@Canvas
        drawCircle(
            color = mint.copy(alpha = alpha.value),
            radius = 30.dp.toPx() * scale.value,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
