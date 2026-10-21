package com.bittool.signer.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.haptics.HapticKind
import com.bittool.signer.core.designsystem.haptics.rememberSignerHaptics
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import kotlinx.coroutines.delay

/**
 * One toast on screen. [id] distinguishes two toasts with the same text; [haptic] is played when it appears (null:
 * none).
 */
@Stable
class StatusToast internal constructor(val id: Long, val message: String, val icon: ImageVector, val haptic: HapticKind?)

/**
 * The state behind [StatusToastHost] (iOS `StatusToast` / `copiedToast` / `statusToast`,
 * `Design/DesignSystem.swift:300-338`). [show] puts a toast up for 1.6 s; a newer toast replaces the current one.
 * Messages are resolved, localised strings ("Copied", "Password Changed", the overlay-blocked notice). Create with
 * [rememberStatusToastState]; not saved across process death (toasts are transient).
 */
@Stable
class StatusToastState {
    private var nextId = 0L

    /** The toast on screen, or null. */
    var current: StatusToast? by mutableStateOf(null)
        private set

    /**
     * Shows [message] with [icon]. [haptic] defaults to the success feedback iOS plays with every status toast
     * (`.sensoryFeedback(.success)`); a toast that reports a problem rather than a completed action passes
     * `HapticKind.Warning` (e.g. `security_overlay_blocked` with `SignerIcons.WarningFilled`, ARCHITECTURE §4.8) or null.
     */
    fun show(message: String, icon: ImageVector = SignerIcons.CheckCircleFilled, haptic: HapticKind? = HapticKind.Success) {
        current = StatusToast(++nextId, message, icon, haptic)
    }

    /** Removes [toast] if it is still the current one (a newer toast is never cut short by an older timer). */
    fun dismiss(toast: StatusToast) {
        if (current?.id == toast.id) current = null
    }
}

@Composable
fun rememberStatusToastState(): StatusToastState = remember { StatusToastState() }

/**
 * Draws [state]'s toast inside the current window, bottom centre: a capsule (light: `card` with a 12 % shadow, blur
 * 12, y 4; dark: the elevated card with a hairline), padding 16 × 10, the icon and the message in subheadline
 * semibold, [bottomSpacing] (24 dp) above the navigation bar or the keyboard. It slides up and fades in with the 0.3 s
 * spring, stays [SignerMotion.TOAST_MILLIS], plays its haptic (success by default) when it appears and is a polite
 * live region, so TalkBack announces it in the current language (iOS posts an English-only announcement; Android
 * announces the localised message). Place it last in the screen's root `Box` so it draws above the content; touches
 * pass through.
 *
 * Inside a `Box` (the usual place) the host overlays the box without taking part in its measurement
 * (`matchParentSize`): the box is as large as its other content, so a sheet that holds a toast host still sizes to its
 * content instead of growing to the full window height (QA sign-11). This overload is picked automatically for a call
 * in a `BoxScope`; the one without a receiver fills the space it is given.
 */
@Composable
fun BoxScope.StatusToastHost(state: StatusToastState, modifier: Modifier = Modifier, bottomSpacing: Dp = 24.dp) {
    StatusToastLayer(state, modifier.matchParentSize(), bottomSpacing)
}

/**
 * [StatusToastHost] outside a `Box`: the host fills the space it is given ([fillMaxSize]) and puts the toast at its
 * bottom centre. Prefer the `BoxScope` overload, which never changes the size of its container.
 */
@Composable
fun StatusToastHost(state: StatusToastState, modifier: Modifier = Modifier, bottomSpacing: Dp = 24.dp) {
    StatusToastLayer(state, modifier, bottomSpacing)
}

@Composable
private fun StatusToastLayer(state: StatusToastState, modifier: Modifier, bottomSpacing: Dp) {
    val haptics = rememberSignerHaptics()
    val toast = state.current
    if (toast != null) {
        LaunchedEffect(toast.id) {
            toast.haptic?.let(haptics::perform)
            delay(SignerMotion.TOAST_MILLIS)
            state.dismiss(toast)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(bottom = bottomSpacing),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedContent(
            targetState = toast,
            transitionSpec = { toastTransition() },
            contentKey = { it?.id },
            label = "statusToast",
        ) { shown ->
            if (shown != null) ToastCapsule(shown.message, icon = shown.icon)
        }
    }
}

private fun toastTransition(): ContentTransform =
    (slideInVertically(SignerMotion.toast()) { it } + fadeIn(SignerMotion.toast())) togetherWith
        (slideOutVertically(SignerMotion.toast()) { it } + fadeOut(SignerMotion.toast()))

/** The toast capsule itself (also used by the design catalog). */
@Composable
fun ToastCapsule(message: String, modifier: Modifier = Modifier, icon: ImageVector = SignerIcons.CheckCircleFilled) {
    val colors = SignerTheme.colors
    val surface = if (colors.isDark) colors.elevatedCard else colors.card
    val decorated = if (colors.isDark) {
        modifier.border(BorderStroke(0.5.dp, colors.separator), SignerShapes.capsule)
    } else {
        modifier.dropShadow(SignerShapes.capsule, Shadow(radius = 12.dp, color = Color.Black.copy(alpha = 0.12f), offset = DpOffset(0.dp, 4.dp)))
    }
    Row(
        modifier = decorated
            .background(surface, SignerShapes.capsule)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // iOS `Label` draws the symbol in the foreground (primary) style, like the text.
        Icon(icon, contentDescription = null, tint = colors.label, modifier = Modifier.size(18.dp))
        Text(message, style = SignerTheme.typography.subheadline.semibold(), color = colors.label)
    }
}
