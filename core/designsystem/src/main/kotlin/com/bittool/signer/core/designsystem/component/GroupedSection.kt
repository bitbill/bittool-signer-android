package com.bittool.signer.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold

/**
 * Builds the rows of a [GroupedSection]. Each [item] is one row; the section draws the hairlines between rows, inset
 * by the row's [dividerInset] (16 dp for plain rows, [IconRowDividerInset] for rows with a leading icon so the line
 * starts under the text, as iOS does).
 */
interface GroupedSectionScope {
    fun item(key: Any? = null, dividerInset: Dp = PlainRowDividerInset, content: @Composable () -> Unit)
}

/** Divider inset under a row without a leading icon. */
val PlainRowDividerInset: Dp = 16.dp

/** Divider inset under a row with a 24 dp leading icon (16 + 24 + 16). */
val IconRowDividerInset: Dp = 56.dp

@Immutable
private class GroupedItem(val key: Any?, val dividerInset: Dp, val content: @Composable () -> Unit)

private class GroupedSectionScopeImpl : GroupedSectionScope {
    val items = mutableListOf<GroupedItem>()
    override fun item(key: Any?, dividerInset: Dp, content: @Composable () -> Unit) {
        items += GroupedItem(key, dividerInset, content)
    }
}

/**
 * The Android rendition of an iOS inset-grouped list section (`List(.insetGrouped)`, `Form`, design-system §5.19,
 * §9.5): an optional [header] (subheadline semibold in `accentOnTint`, sentence case, 16 dp in, marked as a heading),
 * one `card` with radius 22 holding the rows separated by opaque hairlines, and an optional [footer] (footnote,
 * secondary). Callouts and full-bleed headers float outside the card: put them next to the section, not in it.
 * Not lazy: a section holds a handful of rows; long lists use `LazyColumn` items with the same row composables.
 */
@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: GroupedSectionScope.() -> Unit,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val items = GroupedSectionScopeImpl().apply(content).items
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                text = header,
                style = typography.subheadline.semibold(),
                color = colors.accentOnTint,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                    .semantics { heading() },
            )
        }
        if (items.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(SignerShapes.card)
                    .background(colors.card),
            ) {
                items.forEachIndexed { index, item ->
                    key(item.key ?: index) {
                        item.content()
                        if (index < items.lastIndex) {
                            Box(
                                Modifier
                                    .padding(start = item.dividerInset)
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(colors.opaqueSeparator),
                            )
                        }
                    }
                }
            }
        }
        if (footer != null) {
            Text(
                text = footer,
                style = typography.footnote,
                color = colors.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

/**
 * A label with its value on the right in secondary (iOS `LabeledContent`); at accessibility text sizes the value moves
 * under the label so neither is cut. One accessibility element.
 */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val stacked = SignerTheme.isAccessibilityTextSize
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = ROW_MIN_HEIGHT)
        .padding(horizontal = 16.dp, vertical = 12.dp)
        .semantics(mergeDescendants = true) {}
    if (stacked) {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = typography.body, color = colors.label)
            Text(value, style = typography.body, color = colors.secondaryLabel)
        }
    } else {
        Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = typography.body, color = colors.label, modifier = Modifier.weight(1f, fill = false))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Text(value, style = typography.body, color = colors.secondaryLabel, textAlign = TextAlign.End)
            }
        }
    }
}

/**
 * A row that opens a page or performs an action (iOS `NavigationLink` with a `Label`, or a destructive list button):
 * optional leading [icon] in the accent (danger when [destructive]), [title] in body, optional [subtitle] (footnote,
 * secondary), optional trailing [value] (secondary) and the mirrored chevron when [showChevron] (default: every
 * non-destructive row, which is how the app marks navigation). 56 dp minimum height, ripple, `Role.Button`.
 */
@Composable
fun NavRow(
    title: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    destructive: Boolean = false,
    showChevron: Boolean = !destructive,
    enabled: Boolean = true,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val tint = when {
        !enabled -> colors.tertiaryLabel
        destructive -> colors.danger
        else -> colors.accent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(enabled = enabled, role = Role.Button, indication = ripple(), interactionSource = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = typography.body,
                color = when {
                    !enabled -> colors.tertiaryLabel
                    destructive -> colors.danger
                    else -> colors.label
                },
            )
            if (subtitle != null) Text(subtitle, style = typography.footnote, color = colors.secondaryLabel)
        }
        if (value != null) {
            Text(value, style = typography.body, color = colors.secondaryLabel, textAlign = TextAlign.End, modifier = Modifier.weight(0.6f, fill = false))
        }
        if (showChevron) {
            Icon(SignerIcons.ChevronRight, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * A switch row (iOS `Toggle` with a `Label`): leading [icon], [title] (and optional [subtitle]), and a Material switch
 * whose checked track is the accent. The whole row toggles (`Role.Switch`, state read by TalkBack).
 */
@Composable
fun SwitchRow(
    title: String,
    icon: ImageVector?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (enabled) colors.accent else colors.tertiaryLabel, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = typography.body, color = if (enabled) colors.label else colors.tertiaryLabel)
            if (subtitle != null) Text(subtitle, style = typography.footnote, color = colors.secondaryLabel)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.accent,
                checkedThumbColor = Color.White,
                checkedBorderColor = colors.accent,
                uncheckedTrackColor = colors.fill,
                uncheckedBorderColor = colors.fill,
                uncheckedThumbColor = Color.White,
            ),
        )
    }
}

private val ROW_MIN_HEIGHT = 56.dp
