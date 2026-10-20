package com.bittool.signer.core.designsystem.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.modifier.pressScale
import com.bittool.signer.core.designsystem.theme.SignerColors
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold

/**
 * The primary call to action (iOS `ActionLabel` in a `.prominentAction()` button, `Design/DesignSystem.swift:47-54,
 * 94-133`): a full-width accent capsule, at least 52 dp high, label `body` semibold in `onAccent`, optional leading
 * [icon] (20 dp, 8 dp gap).
 *
 * - [busy]: a spinner replaces the content while the label stays laid out (opacity 0) so the width never changes and
 *   TalkBack still reads the label; taps are ignored while busy. The spinner is `onAccent`, or `secondaryLabel` when
 *   disabled (iOS tints it the same way).
 * - Disabled: a neutral fill with a `secondaryLabel` label (iOS: white on a pale disabled fill would vanish).
 * - The label keeps one line, shrinking to 80 % before it ellipsizes; at accessibility text sizes it may take two.
 * - No haptic (iOS plays none on buttons).
 *
 * [fillWidth] `false` wraps the content (used with `widthIn(max = …)` for the "Try Again" button of a failure state).
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    busy: Boolean = false,
    enabled: Boolean = true,
    fillWidth: Boolean = true,
) {
    val colors = SignerTheme.colors
    SignerButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        busy = busy,
        enabled = enabled,
        fillWidth = fillWidth,
        container = if (enabled) colors.accent else disabledFill(colors),
        content = if (enabled) colors.onAccent else colors.secondaryLabel,
    )
}

/**
 * The secondary action with the geometry of [PrimaryButton] (iOS `.secondaryAction()`, `.bordered` capsule): a tonal
 * accent fill with an `accentOnTint` label (the Android rendition of a tinted bordered button). Used on Welcome,
 * Connect, backup, and disabled as "Cannot Sign".
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    busy: Boolean = false,
    enabled: Boolean = true,
    fillWidth: Boolean = true,
) {
    val colors = SignerTheme.colors
    SignerButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        busy = busy,
        enabled = enabled,
        fillWidth = fillWidth,
        container = if (enabled) tonalFill(colors) else disabledFill(colors),
        content = if (enabled) colors.accentOnTint else colors.secondaryLabel,
    )
}

/** Accent at 14 % on the surface: the tonal fill of secondary and pill buttons. */
internal fun tonalFill(colors: SignerColors): Color = colors.accent.copy(alpha = 0.14f)

private fun disabledFill(colors: SignerColors): Color = colors.label.copy(alpha = 0.12f)

@Composable
private fun SignerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    busy: Boolean,
    enabled: Boolean,
    fillWidth: Boolean,
    container: Color,
    content: Color,
) {
    val typography = SignerTheme.typography
    val interaction = remember { MutableInteractionSource() }
    val accessibilityText = SignerTheme.isAccessibilityTextSize
    val labelStyle = typography.body.semibold()
    Surface(
        onClick = { if (!busy) onClick() },
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = SignerTheme.metrics.buttonHeight)
            .pressScale(interaction, scale = 0.97f, alpha = 1f)
            .semantics {
                if (busy) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
        enabled = enabled,
        shape = SignerShapes.capsule,
        color = container,
        contentColor = content,
        interactionSource = interaction,
    ) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.alpha(if (busy) 0f else 1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
                ShrinkingLabel(text, labelStyle.copy(color = content), maxLines = if (accessibilityText) 2 else 1)
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).clearAndSetSemantics {},
                    color = if (enabled) content else SignerTheme.colors.secondaryLabel,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** One line (or [maxLines]) that shrinks to 80 % before ellipsizing (iOS `minimumScaleFactor(0.8)`). */
@Composable
internal fun ShrinkingLabel(
    text: String,
    style: TextStyle,
    maxLines: Int = 1,
    minScale: Float = 0.8f,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
) {
    val size = style.fontSize
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(textAlign = textAlign),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = size * minScale, maxFontSize = size),
    )
}

/**
 * A small tonal pill (iOS wallet-card actions "Pair" / "Addresses", `.bordered` `.regular` capsule with
 * `CompactActionLabelStyle`, `Features/Wallets/WalletsTab.swift:108-127,142-153`): icon + `subheadline` semibold
 * label, 6 dp apart, shrinking to 85 %. At accessibility text sizes only the icon is shown; [text] stays the
 * accessibility label. 48 dp touch target.
 */
@Composable
fun TonalPillButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val iconOnly = SignerTheme.isAccessibilityTextSize
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 40.dp)
            .pressScale(interaction, scale = 0.97f, alpha = 1f)
            .then(if (iconOnly) Modifier.semantics { contentDescription = text } else Modifier),
        shape = SignerShapes.capsule,
        color = tonalFill(colors),
        contentColor = colors.accentOnTint,
        interactionSource = interaction,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (iconOnly) 12.dp else 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.accentOnTint)
            if (!iconOnly) {
                ShrinkingLabel(text, SignerTheme.typography.subheadline.semibold().copy(color = colors.accentOnTint), minScale = 0.85f)
            }
        }
    }
}

/**
 * A text action for top bars and inline links (iOS toolbar `Button("Cancel" / "Done" / "Reject")`, plain tinted
 * buttons such as "Export as File"): accent, or danger when [destructive] (Reject, Clear). [emphasized] uses semibold
 * for the confirming action of a bar ("Done").
 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    emphasized: Boolean = false,
    icon: ImageVector? = null,
) {
    val colors = SignerTheme.colors
    val color = if (destructive) colors.danger else colors.accent
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = colors.tertiaryLabel),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Box(Modifier.size(6.dp))
        }
        val style = SignerTheme.typography.body.let { if (emphasized) it.semibold() else it }
        Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
