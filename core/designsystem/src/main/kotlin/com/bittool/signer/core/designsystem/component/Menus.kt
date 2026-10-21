package com.bittool.signer.core.designsystem.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.window.appExposesTestTags
import com.bittool.signer.core.designsystem.window.rememberWindowGateReleased
import com.bittool.signer.core.designsystem.window.windowTestTags

/**
 * The app's pop-up menu (iOS `Menu` / `contextMenu`): Material 3 `DropdownMenu` anchored to the enclosing layout, on
 * the elevated card colour (design-system §9.2: not Material's `surfaceContainer`, which is the page colour), shown
 * while [expanded].
 *
 * Same window rules as [SignerAlertDialog]: the menu is a popup window of its own, so it waits for
 * `LocalWindowGateOpen` (a menu requested while App Lock is up appears after unlock) and, in debuggable builds,
 * publishes the test tags of its items as resource ids itself (`windowTestTags`; the activity root's flag does not
 * reach a separate window, backlog B26). [modifier] applies to the menu's content inside that window.
 */
@Composable
fun SignerDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded || !rememberWindowGateReleased()) return
    val exposeTestTags = appExposesTestTags()
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismissRequest,
        modifier = modifier.windowTestTags(exposeTestTags),
        offset = offset,
        containerColor = SignerTheme.colors.elevatedCard,
        content = content,
    )
}
