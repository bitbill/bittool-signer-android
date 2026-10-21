package com.bittool.signer.root

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.bittool.signer.core.designsystem.brand.LaunchView
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.window.LocalWindowGateOpen
import com.bittool.signer.core.model.TokenIconSource
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.ui.logo.LocalTokenIconSource
import com.bittool.signer.feature.onboarding.OnboardingPager
import com.bittool.signer.feature.onboarding.WelcomeScreen
import com.bittool.signer.feature.setup.SetupFlowHost
import com.bittool.signer.main.MainScaffold
import com.bittool.signer.core.ui.theme.SignerTheme as AppearanceTheme

/** The stage contents (the real screens in the app; stand-ins in screenshot and glue tests). */
class RootSlots(
    val welcome: @Composable (onStart: (SetupIntent) -> Unit) -> Unit = { WelcomeScreen(onStart = it) },
    val main: @Composable (viewModel: RootViewModel, showsScanBadge: Boolean) -> Unit = { viewModel, badge ->
        MainScaffold(router = viewModel.router, showsScanBadge = badge, canOpenCamera = viewModel::canOpenCamera)
    },
    val introduction: IntroductionContent = { p, complete, cancel -> OnboardingPager(p.intent, complete, cancel) },
    val steps: SetupStepsContent = { p, finished, cancelled -> SetupFlowHost(p, finished, cancelled) },
)

/**
 * The root composition of the main window (iOS `RootView`, app-shell.md §4; ARCHITECTURE §2.1), bottom to top:
 * 1. the stage — `LaunchView` while loading, "Wallets Unavailable", Welcome, or the tabs — cross-fading on a change
 *    (iOS `.smooth(duration: 0.35)`);
 * 2. the setup overlay (above the stages, so creating the first wallet can switch Welcome → tabs underneath it);
 * 3. the privacy cover while not resumed or while the screen is recorded;
 * 4. "Unable to Continue".
 * Layers 1, 2 and 4 are inside the window gate (`LocalWindowGateOpen`): their dialogs and sheets wait while App Lock is
 * up. While locked the content is invisible and out of the accessibility tree (iOS `.opacity(0)`,
 * `.accessibilityHidden`): the lock window, a separate window above every dialog, is the only surface.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SignerRoot(
    viewModel: RootViewModel,
    tokenIcons: TokenIconSource,
    exposeTestTags: Boolean,
    slots: RootSlots = RootSlots(),
) {
    // Not lifecycle-gated: the lock and cover must be right in the first frame after onStart (Ownbit OwnbitAppRoot).
    val ui by viewModel.uiState.collectAsState()
    AppearanceTheme(appearance = ui.appearance) {
        CompositionLocalProvider(LocalTokenIconSource provides tokenIcons) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SignerTheme.colors.page)
                    .semantics { testTagsAsResourceId = exposeTestTags }
                    .testTag(RootTags.ROOT),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (ui.locked) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier),
                ) {
                    CompositionLocalProvider(LocalWindowGateOpen provides ui.gateOpen) {
                        Box(Modifier.fillMaxSize().then(if (ui.setup != null) Modifier.clearAndSetSemantics {} else Modifier)) {
                            StageHost(ui, viewModel, slots)
                        }
                        SetupOverlay(
                            presentation = ui.setup,
                            onIntroductionCompleted = viewModel::onIntroductionCompleted,
                            onFinished = viewModel::finishSetup,
                            onCancelled = viewModel::cancelSetup,
                            onDismissed = viewModel::setupDidDismiss,
                            introduction = slots.introduction,
                            steps = slots.steps,
                        )
                    }
                }
                PrivacyCover(visible = ui.coverVisible, captured = ui.captured)
                CompositionLocalProvider(LocalWindowGateOpen provides ui.gateOpen) {
                    ui.error?.let { error -> UnableToContinueDialog(error = error, onDismiss = viewModel::dismissError) }
                }
            }
        }
    }
}

@Composable
private fun StageHost(ui: RootUiState, viewModel: RootViewModel, slots: RootSlots) {
    Crossfade(targetState = ui.stage, animationSpec = SignerMotion.stage(), label = "stage") { stage ->
        when (stage) {
            RootStage.Loading -> LaunchView()
            RootStage.Failed -> LoadFailureScreen(retrying = ui.retrying, onRetry = viewModel::retryLoad)
            RootStage.Welcome -> slots.welcome(viewModel::startSetup)
            RootStage.Main -> slots.main(viewModel, ui.showsScanBadge)
        }
    }
}
