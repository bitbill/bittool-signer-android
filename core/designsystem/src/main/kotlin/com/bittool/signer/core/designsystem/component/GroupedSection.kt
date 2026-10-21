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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.contentDirection
import com.bittool.signer.core.designsystem.theme.localizedDirection
import com.bittool.signer.core.designsystem.theme.phraseBreakable
import com.bittool.signer.core.designsystem.theme.phraseLineBreaks
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
 * secondary; laid out with `localizedDirection`, so in a right-to-left interface a footer that has no translation yet
 * keeps its full stop at its end, QA sign-02). Callouts and full-bleed headers float outside the card: put them next
 * to the section, not in it. Not lazy: a section holds a handful of rows; long lists use `LazyColumn` items with the
 * same row composables.
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
                style = typography.footnote.localizedDirection(footer),
                color = colors.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

/**
 * A label with its value against the trailing edge in secondary (iOS `LabeledContent`, [LabelValueLayout]); at
 * accessibility text sizes the value moves under the label so neither is cut. One accessibility element. A [verbatim]
 * value (a version, a network or wallet name, a fingerprint, a decoded value: text that is not translated) is laid out
 * in its own direction (`contentDirection`), so in a right-to-left interface "Ethereum (Mainnet)" keeps its bracket at
 * the end; a translated value keeps the interface direction. Text without any strong character (a bare version such
 * as "1.0.0 (1)") has no direction of its own and still follows the interface.
 */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, verbatim: Boolean = false) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val stacked = SignerTheme.isAccessibilityTextSize
    val valueStyle = if (verbatim) typography.body.contentDirection() else typography.body
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = ROW_MIN_HEIGHT)
        .padding(horizontal = 16.dp, vertical = 12.dp)
        .semantics(mergeDescendants = true) {}
    if (stacked) {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = typography.body, color = colors.label)
            Text(value, style = valueStyle, color = colors.secondaryLabel)
        }
    } else {
        LabelValueLayout(
            modifier = rowModifier,
            label = { Text(label, style = typography.body, color = colors.label) },
            value = { Text(value, style = valueStyle, color = colors.secondaryLabel, textAlign = TextAlign.End) },
        )
    }
}

/**
 * A row that opens a page or performs an action (iOS `NavigationLink` with a `Label`, or a destructive list button):
 * optional leading [icon] in the accent (danger when [destructive]), [title] in body, optional [subtitle] (footnote,
 * secondary), optional trailing [value] (secondary, against the chevron like a menu `Picker` in a `Form`; laid out in its
 * own direction when [verbatimValue], see [ValueRow]) and the mirrored chevron when [showChevron] (default: every
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
    verbatimValue: Boolean = false,
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
        // At accessibility text sizes the value moves under the title instead of squeezing it. Beside the title, title
        // and value share the free width ([LabelValueLayout]): the value ends at the chevron, and a long value never
        // takes the title's width.
        val stackValue = SignerTheme.isAccessibilityTextSize
        val valueStyle = if (verbatimValue) typography.body.contentDirection() else typography.body
        val titleBlock: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                if (value != null && stackValue) Text(value, style = valueStyle, color = colors.secondaryLabel)
            }
        }
        if (value != null && !stackValue) {
            LabelValueLayout(
                modifier = Modifier.weight(1f),
                label = titleBlock,
                value = {
                    Text(
                        value,
                        style = valueStyle,
                        color = colors.secondaryLabel,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        } else {
            Box(Modifier.weight(1f)) { titleBlock() }
        }
        if (showChevron) {
            Icon(SignerIcons.ChevronRight, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * A switch row (iOS `Toggle` with a `Label`): leading [icon], [title] (and optional [subtitle]), and a Material switch
 * whose checked track is the accent. The whole row toggles (`Role.Switch`, state read by TalkBack). The subtitle breaks
 * Japanese and Korean lines between phrases, also below API 33 (`phraseLineBreaks`, `phraseBreakable`; backlog B31):
 * "「25 番目の単語」を / 追加します。", never "…を追加しま / す。".
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
            if (subtitle != null) {
                Text(
                    text = phraseBreakable(subtitle),
                    style = typography.footnote.phraseLineBreaks(),
                    color = colors.secondaryLabel,
                )
            }
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

/**
 * The label / value pair of [ValueRow] and [NavRow] (iOS `LabeledContent`): [label] at the start and [value] against
 * the end edge of the whole width, [gap] apart, both centred vertically. Widths follow [labelValueWidths]: each keeps its
 * natural width when both fit; otherwise the side that needs less than half keeps its width and the other wraps in the
 * rest, and two long texts share the row equally. (A `Row` with two weights gave the value half of the free width and
 * left the rest unused after a short label, so values and chevrons ended mid-row.) Mirrored in right-to-left layouts.
 */
@Composable
internal fun LabelValueLayout(
    modifier: Modifier,
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
    gap: Dp = 12.dp,
) {
    Layout(
        modifier = modifier,
        content = {
            Box { label() }
            Box { value() }
        },
    ) { measurables, constraints ->
        val (labelMeasurable, valueMeasurable) = measurables
        val gapPx = gap.roundToPx()
        val labelWant = labelMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val valueWant = valueMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            (labelWant + gapPx + valueWant).coerceAtLeast(constraints.minWidth)
        }
        val (labelWidth, valueWidth) = labelValueWidths((width - gapPx).coerceAtLeast(0), labelWant, valueWant)
        val labelPlaceable = labelMeasurable.measure(Constraints(maxWidth = labelWidth))
        val valuePlaceable = valueMeasurable.measure(Constraints(maxWidth = valueWidth))
        val height = maxOf(labelPlaceable.height, valuePlaceable.height, constraints.minHeight)
        layout(width, height) {
            labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
            valuePlaceable.placeRelative(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
        }
    }
}

/**
 * How [LabelValueLayout] shares [available] pixels between a label that wants [label] and a value that wants [value]
 * (their one-line widths): both natural when they fit; else the one needing at most half keeps its width and the other
 * gets the rest; else half each. Pure, unit-tested.
 */
internal fun labelValueWidths(available: Int, label: Int, value: Int): Pair<Int, Int> {
    if (label + value <= available) return label to value
    val half = available / 2
    return when {
        label <= half -> label to available - label
        value <= half -> available - value to value
        else -> half to available - half
    }
}

private val ROW_MIN_HEIGHT = 56.dp
