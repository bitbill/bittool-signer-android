package com.bittool.signer.feature.setup.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.security.RecoveryPrivacySession
import com.bittool.signer.feature.setup.ui.BodyText
import com.bittool.signer.feature.setup.ui.LocalRecoverySessions
import com.bittool.signer.core.l10n.R as L10n

/** A display-permission session for one concealed display, created once per composition of that display. */
@Composable
internal fun rememberPrivacySession(): RecoveryPrivacySession {
    val factory = LocalRecoverySessions.current
    return remember(factory) { factory.privacySession() }
}

/**
 * iOS `recoveryPrivacy(session, onReveal:)` (`RecoveryPhraseViews.swift:316-345`): while [active] (shown and not
 * covered by a sheet) the session is mounted, which conceals on pause, background, capture and screenshots; leaving or
 * covering the display stops it (and conceals). [onReveal] runs whenever the content becomes visible. A screenshot
 * prompt is the "Screenshot Detected" alert; its OK acknowledges it (a reveal is refused until then).
 */
@Composable
internal fun RecoveryPrivacyEffect(session: RecoveryPrivacySession, active: Boolean, onReveal: () -> Unit = {}) {
    DisposableEffect(session, active) {
        if (active) session.start()
        onDispose { if (active) session.stop() }
    }
    val revealed by session.isRevealed.collectAsStateWithLifecycle()
    val latestOnReveal by rememberUpdatedState(onReveal)
    LaunchedEffect(revealed) { if (revealed) latestOnReveal() }
    val prompt by session.prompt.collectAsStateWithLifecycle()
    if (prompt != null) {
        ScreenshotDetectedDialog(
            message = stringResource(L10n.string.setup_screenshot_phrase_hidden),
            onAcknowledge = session::acknowledgeWarning,
        )
    }
}

/**
 * iOS `recoveryScreenshotWarning(onScreenshot:)` (`RecoveryPhraseViews.swift:350-380`) for directly visible recovery
 * material (the import editor, the backup check): [onScreenshot] runs on every screenshot, then — while the screen is
 * resumed — the "may contain" alert asks to delete it. The content's visibility does not change. Only the current
 * warning's OK acknowledges it.
 */
@Composable
internal fun RecoveryScreenshotWarning(onScreenshot: () -> Unit = {}) {
    val factory = LocalRecoverySessions.current
    val session = remember(factory) { factory.screenshotSession() }
    val latest by rememberUpdatedState(onScreenshot)
    DisposableEffect(session) {
        session.start { latest() }
        onDispose { session.stop() }
    }
    val warning by session.warning.collectAsStateWithLifecycle()
    warning?.let { current ->
        ScreenshotDetectedDialog(
            message = stringResource(L10n.string.setup_screenshot_may_contain),
            onAcknowledge = { session.acknowledge(current) },
        )
    }
}

/** The "Screenshot Detected" alert (secure window: it sits over recovery material). */
@Composable
private fun ScreenshotDetectedDialog(message: String, onAcknowledge: () -> Unit) {
    SignerAlertDialog(
        title = stringResource(L10n.string.setup_screenshot_detected),
        text = message,
        confirm = DialogAction(stringResource(L10n.string.ok), testTag = "recovery.screenshot.ok", onClick = onAcknowledge),
        secure = true,
        onDismissRequest = onAcknowledge,
    )
}

/**
 * "Tap to Reveal" badge shared by every concealed backup view (iOS `RevealPrompt`, `RecoveryBackupViews.swift:157-169`):
 * the eye-slash glyph, "Tap to Reveal" (headline) and "Make sure no one can see your screen." (footnote, secondary) on
 * a frosted plate, radius 18. Decorative for accessibility: the concealed button carries the label.
 */
@Composable
internal fun RevealPrompt(modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val plate = if (colors.isDark) colors.elevated().raised else colors.card
    val iconSize = with(LocalDensity.current) { (typography.title2.fontSize * 1.1f).toDp() }
    Column(
        modifier = modifier
            .clearAndSetSemantics {}
            .then(
                if (colors.isDark) {
                    Modifier
                } else {
                    Modifier.dropShadow(PromptShape, Shadow(radius = 14.dp, color = Color.Black.copy(alpha = 0.10f), offset = DpOffset(0.dp, 4.dp)))
                },
            )
            .background(plate.copy(alpha = 0.94f), PromptShape)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(SignerIcons.VisibilityOff, contentDescription = null, tint = colors.label, modifier = Modifier.size(iconSize))
        Text(stringResource(L10n.string.tap_to_reveal), style = typography.headline, color = colors.label, textAlign = TextAlign.Center)
        BodyText(
            stringResource(L10n.string.make_sure_no_one_can_see_your_screen_2),
            style = typography.footnote,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}

/** The accessibility name of a concealed button: its label, then (iOS `accessibilityHint`) the safety hint. */
@Composable
internal fun concealedDescription(label: Int): String =
    stringResource(label) + "\n" + stringResource(L10n.string.make_sure_no_one_can_see_your_screen)

private val PromptShape = RoundedCornerShape(18.dp)
