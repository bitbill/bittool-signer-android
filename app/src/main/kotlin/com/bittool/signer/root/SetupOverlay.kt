package com.bittool.signer.root

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.navigation.SetupPresentation
import com.bittool.signer.core.navigation.WalletRoute
import com.bittool.signer.feature.onboarding.OnboardingPager
import com.bittool.signer.feature.setup.SetupFlowHost

/** The introduction slot of [SetupOverlay] (the onboarding pager in the app; a stand-in in tests). */
typealias IntroductionContent = @Composable (presentation: SetupPresentation, onComplete: () -> Unit, onCancel: () -> Unit) -> Unit

/** The setup-steps slot of [SetupOverlay] (`:feature:setup`'s `SetupFlowHost` in the app). */
typealias SetupStepsContent = @Composable (presentation: SetupPresentation, onFinished: (WalletRoute?) -> Unit, onCancelled: () -> Unit) -> Unit

/**
 * The wallet setup presentation (iOS `.sheet(item: router.setup)` on RootView, RootView.swift:41; app-shell.md §7,
 * §15.10; ARCHITECTURE §6 item 8): a full-screen layer above the stages, so it survives the Welcome → tabs switch
 * that creating the first wallet causes underneath. It slides up when a presentation starts and down when it ends;
 * each presentation (`SetupPresentation.id`) gets fresh content. Nothing of it is saved across process death.
 *
 * - First the introduction when `showsIntroduction` (cross-fading into the steps after "Get Started", which also calls
 *   [onIntroductionCompleted]); then the steps of the same intent, entering from the end edge.
 * - [onFinished] / [onCancelled] map to `AppRouter.finishSetup` / `cancelSetup`; [onDismissed] runs once the exit
 *   animation has finished (iOS `onDismiss` → `setupDidDismiss`, which only then opens "Pair a Wallet"'s page:
 *   "Pushing while the sheet is still animating away can leave the push transition stuck mid-way").
 * - The layer takes every touch, and the stage below is hidden from accessibility by the root while it is up. Back is
 *   the content's (onboarding: previous page or Cancel; setup: setup-backup.md §15.4); a content without its own
 *   handler cancels.
 */
@Composable
fun SetupOverlay(
    presentation: SetupPresentation?,
    onIntroductionCompleted: () -> Unit,
    onFinished: (WalletRoute?) -> Unit,
    onCancelled: () -> Unit,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    introduction: IntroductionContent = { p, complete, cancel -> OnboardingPager(p.intent, complete, cancel) },
    steps: SetupStepsContent = { p, finished, cancelled -> SetupFlowHost(p, finished, cancelled) },
) {
    val visibility = remember { MutableTransitionState(presentation != null) }
    visibility.targetState = presentation != null
    // The presentation drawn while the layer slides away (the router has already cleared it).
    val shown = remember { ShownPresentation() }
    if (presentation != null) shown.value = presentation
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    val exited = visibility.isIdle && !visibility.currentState
    LaunchedEffect(exited) {
        if (exited && shown.value != null) {
            shown.value = null
            currentOnDismissed()
        }
    }
    val spec = SignerMotion.smooth<IntOffset>(OVERLAY_SECONDS)
    AnimatedVisibility(
        visibleState = visibility,
        modifier = modifier,
        enter = slideInVertically(spec) { it },
        exit = slideOutVertically(spec) { it },
    ) {
        val current = shown.value ?: return@AnimatedVisibility
        key(current.id) {
            SetupContainer(current, leaving = presentation == null, onIntroductionCompleted, onFinished, onCancelled, introduction, steps)
        }
    }
}

@Composable
private fun SetupContainer(
    presentation: SetupPresentation,
    leaving: Boolean,
    onIntroductionCompleted: () -> Unit,
    onFinished: (WalletRoute?) -> Unit,
    onCancelled: () -> Unit,
    introduction: IntroductionContent,
    steps: SetupStepsContent,
) {
    var introductionDone by rememberSaveable(presentation.id) { mutableStateOf(!presentation.showsIntroduction) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        Modifier
            .fillMaxSize()
            .background(SignerTheme.colors.page)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .testTag(RootTags.SETUP),
    ) {
        // Registered before the content's own handlers, so those take precedence; only a content without one ends here.
        BackHandler(enabled = !leaving, onBack = onCancelled)
        AnimatedContent(
            targetState = introductionDone,
            transitionSpec = {
                val slide = SignerMotion.smooth<IntOffset>(STEPS_SECONDS)
                (slideInHorizontally(slide) { if (rtl) -it else it } + fadeIn(SignerMotion.smooth(STEPS_SECONDS))) togetherWith
                    fadeOut(SignerMotion.smooth(STEPS_SECONDS))
            },
            label = "setupIntroduction",
        ) { done ->
            if (done) {
                steps(presentation, onFinished, onCancelled)
            } else {
                introduction(
                    presentation,
                    {
                        onIntroductionCompleted()
                        introductionDone = true
                    },
                    onCancelled,
                )
            }
        }
        // While the layer slides away (finished or cancelled), Back belongs to nothing in it: a late Back must not
        // cancel a finished setup and lose the page "Pair a Wallet" opens once the layer has gone. Registered when
        // leaving starts, so it is the newest handler and is asked before the content's own ones.
        if (leaving) BackHandler {}
    }
}

/** Not state: the presentation is only read while composing for the current one. */
private class ShownPresentation {
    var value: SetupPresentation? = null
}

/** iOS sheet presentation ≈ 0.4 s, without a bounce. */
private const val OVERLAY_SECONDS = 0.4

/** iOS `.smooth` (0.5 s) into the steps. */
private const val STEPS_SECONDS = 0.5
