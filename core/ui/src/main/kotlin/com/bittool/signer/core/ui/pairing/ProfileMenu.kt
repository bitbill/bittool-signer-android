package com.bittool.signer.core.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.window.rememberWindowGateReleased

/**
 * A menu picker presented as a full-width row (iOS `ProfileMenu`, `Features/Wallets/ConnectView.swift:547-597`;
 * wallets-pairing §3.7) for option lists that do not fit a segmented control: [title] (subheadline) over the current
 * option's [detail] (caption, secondary) on the start side; the current option's [label] and an up-down chevron in the
 * accent on the end side; card colour, radius 14, padding 14 × 10. At accessibility text sizes the halves stack. The
 * whole row opens a menu anchored to it: each option's label with its detail as supporting text and a check on the
 * current one. TalkBack reads [title] with the value "label, detail"; tag `profile.menu`.
 */
@Composable
fun <T> ProfileMenu(
    title: String,
    options: List<T>,
    selection: T,
    label: (T) -> String,
    detail: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val stacked = SignerTheme.isAccessibilityTextSize
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = label(selection)
    val currentDetail = detail(selection)
    Box(modifier.fillMaxWidth()) {
        val rowModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(SignerShapes.control)
            .background(colors.card)
            .clickable { expanded = true }
            .clearAndSetSemantics {
                role = Role.DropdownList
                contentDescription = title
                stateDescription = "$currentLabel, $currentDetail"
            }
            .testTag("profile.menu")
            .padding(horizontal = 14.dp, vertical = 10.dp)
        val titleBlock: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = typography.subheadline, color = colors.label)
                Text(currentDetail, style = typography.caption, color = colors.secondaryLabel)
            }
        }
        val valueBlock: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(currentLabel, style = typography.subheadline, color = colors.accent)
                Icon(SignerIcons.UnfoldMore, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
            }
        }
        if (stacked) {
            Column(rowModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                titleBlock()
                valueBlock()
            }
        } else {
            Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { titleBlock() }
                valueBlock()
            }
        }
        if (expanded && rememberWindowGateReleased()) {
            DropdownMenu(expanded = true, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    val isSelected = option == selection
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(label(option), style = typography.body, color = colors.label)
                                Text(detail(option), style = typography.footnote, color = colors.secondaryLabel)
                            }
                        },
                        trailingIcon = if (isSelected) {
                            { Icon(SignerIcons.Check, contentDescription = null, tint = colors.accent) }
                        } else {
                            null
                        },
                        onClick = {
                            expanded = false
                            if (!isSelected) onSelect(option)
                        },
                        modifier = Modifier.semantics { selected = isSelected },
                    )
                }
            }
        }
    }
}
