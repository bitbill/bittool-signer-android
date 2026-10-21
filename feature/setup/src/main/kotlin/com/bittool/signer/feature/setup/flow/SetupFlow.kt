package com.bittool.signer.feature.setup.flow

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.haptics.rememberSignerHaptics
import com.bittool.signer.core.designsystem.security.ExcludeFromAutofill
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.navigation.WalletRoute
import com.bittool.signer.core.l10n.resolve
import com.bittool.signer.feature.setup.ui.LocalRecoverySessions
import com.bittool.signer.feature.setup.ui.OverlayGuardHost
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import com.bittool.signer.core.l10n.R as L10n

/** The visible page of the flow: the root form ([step] null) or a pushed step, at its depth in the path. */
@Immutable
internal data class SetupScreen(val step: SetupStep?, val depth: Int)

internal val WalletSetupUiState.screen: SetupScreen get() = SetupScreen(step, path.size)

/**
 * The setup flow of one presentation (iOS `WalletSetupFlow` body without the introduction, setup-backup §1): the
 * ViewModel-owned step path rendered with a shared-axis transition, the predictive back gesture popping pushed steps
 * (§15.4), Cancel / "Discard this wallet?", and the host results ([onFinished], [onCancelled]).
 */
@Composable
internal fun SetupFlow(
    model: WalletSetupViewModel,
    onFinished: (WalletRoute?) -> Unit,
    onCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val haptics = rememberSignerHaptics()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestFinished by rememberUpdatedState(onFinished)
    val latestCancelled by rememberUpdatedState(onCancelled)
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.eventFlow.collect { event ->
                when (event) {
                    SetupEvent.Close -> latestCancelled()
                    is SetupEvent.Finish -> latestFinished(event.route)
                    SetupEvent.VerificationRejected -> haptics.reject()
                    SetupEvent.Saved -> haptics.success()
                }
            }
        }
    }
    val resources = LocalResources.current
    val defaultName = remember(model, resources) { model.defaultName { it.resolve(resources) } }
    LaunchedEffect(model) { model.seedName(defaultName) }
    // Back from "Open Settings" (or anything else): a screen lock set meanwhile unlocks the first step (QA wallets-04).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refreshDeviceSecurity() }

    // How far a back gesture that closes setup has gone (0 = none): the whole surface shrinks with the finger.
    val closePreview = remember { Animatable(0f) }
    CompositionLocalProvider(LocalRecoverySessions provides model.recoverySessions) {
        OverlayGuardHost(modifier.closePreview(closePreview).background(SignerTheme.colors.page)) {
            // No Autofill offer reaches a passphrase or a new wallet password (setup-backup §15.7).
            ExcludeFromAutofill()
            SetupSteps(state, model, closePreview)
        }
        if (state.confirmDiscard) {
            DiscardWalletDialog(state.intent, onDiscard = model::discard, onKeepEditing = model::keepEditing)
        }
    }
}

/**
 * Renders [state]'s step path. Pushes and wholesale replacements animate on the shared X axis (forward when the path
 * grows); system Back is the predictive gesture (setup-backup §15.4): on a pushed step the previous page follows the
 * finger and a committed gesture pops, a cancelled one springs back. Where Back ends the presentation (the form without
 * a draft, the Done step) the surface shrinks with the finger ([closePreview], "back to previous") and a committed
 * gesture is Cancel / "Done"; with a draft it asks "Discard this wallet?" and while busy it is consumed, both without a
 * preview (the system must not preview leaving).
 */
@Composable
private fun SetupSteps(state: WalletSetupUiState, model: WalletSetupViewModel, closePreview: Animatable<Float, AnimationVector1D>) {
    val screen = state.screen
    val transitionState = remember { SeekableTransitionState(screen) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val latestState by rememberUpdatedState(state)
    var seeking by remember { mutableStateOf(false) }

    LaunchedEffect(screen) {
        focusManager.clearFocus()
        if (!seeking) transitionState.animateTo(screen)
    }

    val reduceMotion = SignerTheme.reduceMotion
    val latestReduceMotion by rememberUpdatedState(reduceMotion)
    PredictiveBackHandler(enabled = true) { progress ->
        val current = latestState
        if (!model.canPop()) {
            val preview = model.backEndsPresentation() && !latestReduceMotion
            try {
                progress.collect { event -> if (preview) closePreview.snapTo(event.progress) }
            } catch (e: CancellationException) {
                scope.launch { closePreview.animateTo(0f, SignerMotion.quick()) }
                throw e
            }
            model.back()
            // The overlay leaves from where the gesture ended; anything else springs back.
            if (!model.hasEnded) scope.launch { closePreview.animateTo(0f, SignerMotion.quick()) }
            return@PredictiveBackHandler
        }
        val previous = SetupScreen(current.path.dropLast(1).lastOrNull(), current.path.size - 1)
        val origin = current.screen
        try {
            seeking = true
            progress.collect { event -> transitionState.seekTo(event.progress, previous) }
            seeking = false
            model.pop()
        } catch (e: CancellationException) {
            seeking = false
            scope.launch { transitionState.animateTo(origin) }
            throw e
        }
    }

    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val distance = remember(density) { with(density) { SHARED_AXIS_DISTANCE.roundToPx() } }
    rememberTransition(transitionState, label = "setupSteps").AnimatedContent(
        transitionSpec = { sharedAxisX(distance, rtl, reduceMotion) },
        contentKey = { it },
    ) { shown ->
        when (shown.step) {
            null -> if (state.intent == SetupIntent.Create) CreateWalletStep(state, model) else ImportWalletStep(state, model)
            SetupStep.Backup -> BackupPhraseStep(state, model)
            SetupStep.Verify -> VerifyBackupStep(state, model)
            SetupStep.Password -> SetPasswordStep(state, model)
            SetupStep.Done -> SetupCompleteStep(state, model)
        }
    }
}

/**
 * Material shared axis X: the incoming page slides 30 dp in from the reading side (from the end when going forward)
 * while the outgoing one fades out early; tween-based, so the back gesture can seek it. Without animations it is a
 * plain cross-fade.
 */
private fun AnimatedContentTransitionScope<SetupScreen>.sharedAxisX(distance: Int, rtl: Boolean, reduceMotion: Boolean): ContentTransform {
    val forward = targetState.depth >= initialState.depth
    if (reduceMotion) return fadeIn(tween(SHARED_AXIS_MILLIS)) togetherWith fadeOut(tween(SHARED_AXIS_MILLIS))
    val sign = if (forward != rtl) 1 else -1
    val slide = tween<IntOffset>(SHARED_AXIS_MILLIS, easing = FastOutSlowInEasing)
    return (slideInHorizontally(slide) { sign * distance } + fadeIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing))) togetherWith
        (slideOutHorizontally(slide) { -sign * distance } + fadeOut(tween(90, easing = FastOutSlowInEasing)))
}

private val SHARED_AXIS_DISTANCE = 30.dp
private const val SHARED_AXIS_MILLIS = 300

/**
 * The predictive "back to previous" preview of a gesture that closes setup: at full progress the surface is at 90 %
 * with large rounded corners, so what setup returns to shows around it (Material predictive back for full-screen
 * surfaces). Read in the draw phase only: the gesture never recomposes the steps.
 */
private fun Modifier.closePreview(progress: Animatable<Float, AnimationVector1D>): Modifier = graphicsLayer {
    val fraction = progress.value
    if (fraction > 0f) {
        val scale = 1f - (1f - CLOSE_PREVIEW_MIN_SCALE) * fraction
        scaleX = scale
        scaleY = scale
        shape = RoundedCornerShape(CLOSE_PREVIEW_CORNER * fraction)
        clip = true
    }
}

private const val CLOSE_PREVIEW_MIN_SCALE = 0.9f
private val CLOSE_PREVIEW_CORNER = 32.dp

/**
 * "Discard this wallet?" (iOS `confirmationDialog`, `WalletSetupFlow.swift:46-53`): "Discard" (destructive) erases the
 * draft and closes the flow; "Keep Editing" (and Back) keeps it.
 */
@Composable
private fun DiscardWalletDialog(intent: SetupIntent, onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    SignerAlertDialog(
        title = stringResource(L10n.string.discard_this_wallet),
        text = stringResource(
            if (intent == SetupIntent.Create) {
                L10n.string.the_recovery_phrase_shown_here_will_be_erased_if_you
            } else {
                L10n.string.the_words_you_entered_will_be_erased_from_this_screen
            },
        ),
        confirm = DialogAction(stringResource(L10n.string.discard), destructive = true, testTag = "setup.discard", onClick = onDiscard),
        dismiss = DialogAction(stringResource(L10n.string.keep_editing), testTag = "setup.keepEditing", onClick = onKeepEditing),
        onDismissRequest = onKeepEditing,
    )
}
