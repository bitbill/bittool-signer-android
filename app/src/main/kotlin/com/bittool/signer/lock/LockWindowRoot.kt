package com.bittool.signer.lock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.model.Appearance
import com.bittool.signer.core.security.ScenePhase
import com.bittool.signer.core.ui.theme.SignerTheme
import com.bittool.signer.root.PrivacyCover
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the App Lock window draws (app-shell.md §11.10, §15.7–§15.8): the lock screen in the in-app appearance
 * (it follows a Theme change live, iOS `PrivacyShieldTests`), and the privacy cover above it while the activity is not
 * resumed or the screen is recorded (iOS draws its shield window above the lock window). It is its own window, so it
 * sets its own test-tag exposure. Not inside the window gate: the lock screen is what the gate waits for.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LockWindowRoot(
    appearance: StateFlow<Appearance>,
    phase: StateFlow<ScenePhase>,
    captured: StateFlow<Boolean>,
    exposeTestTags: Boolean,
    content: @Composable () -> Unit = { AppLockScreen() },
) {
    val theme by appearance.collectAsStateWithLifecycle()
    val scenePhase by phase.collectAsStateWithLifecycle()
    val recorded by captured.collectAsStateWithLifecycle()
    SignerTheme(appearance = theme) {
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = exposeTestTags }) {
            content()
            PrivacyCover(visible = scenePhase != ScenePhase.Active || recorded, captured = recorded)
        }
    }
}
