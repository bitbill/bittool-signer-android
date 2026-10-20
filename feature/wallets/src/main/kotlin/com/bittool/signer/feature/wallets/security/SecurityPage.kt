package com.bittool.signer.feature.wallets.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bittool.signer.core.designsystem.component.NavIcon
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.component.StatusToastHost
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme

/**
 * The frame of the wallet security pages (iOS sheets with their own bar and a leading "Cancel"; modal routes on
 * Android, ARCHITECTURE §6 item 7): an inline title, the close button, a scrolling form that stays above the keyboard,
 * and the page's toast. While [busy] neither the close button nor Back leaves (iOS `interactiveDismissDisabled`);
 * otherwise the close button runs [onCancel] (cancel, wipe, pop) and Back pops with the predictive animation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SecurityPage(
    title: String,
    busy: Boolean,
    onCancel: () -> Unit,
    toast: StatusToastState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // Back while busy is consumed (the operation commits or fails first). Otherwise system Back is Navigation's own
    // (predictive) pop: the fields are wiped when the page leaves and the ViewModel cancels its operation when cleared.
    BackHandler(enabled = busy) {}
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = SignerTheme.colors.page,
        topBar = {
            SignerTopAppBar(
                title = title,
                navigation = NavIcon.Close,
                onNavigate = { if (!busy) onCancel() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = SignerMetrics.pagePadding, vertical = 8.dp)
                    .padding(bottom = SignerMetrics.listBottomInset),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.readableWidth(), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
            }
            StatusToastHost(toast)
        }
    }
}

/** A footnote under a form group (the iOS section footer). */
@Composable
internal fun FormFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SignerTheme.typography.footnote,
        color = SignerTheme.colors.secondaryLabel,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

/**
 * The typed passwords of one security page, in composition memory only (`remember`, never saved: ARCHITECTURE §4.4).
 * [clear] empties every field.
 */
@Stable
internal class PasswordFields(count: Int) {
    val fields: List<TextFieldState> = List(count) { TextFieldState() }

    operator fun get(index: Int): TextFieldState = fields[index]

    fun clear() = fields.forEach { it.clearText() }
}

/**
 * [count] password fields that are cleared when the page leaves, when the app stops and on every suspension
 * ([securityEpoch] renewed by background, App Lock or capture) — except while [busy], when the operation that already
 * holds copies decides (iOS `onChange(of: store.securityEpoch) { if !operation.busy { … } }`).
 */
@Composable
internal fun rememberPasswordFields(count: Int, securityEpoch: Long, busy: Boolean): PasswordFields {
    val fields = remember { PasswordFields(count) }
    val isBusy by rememberUpdatedState(busy)
    LaunchedEffect(securityEpoch) { if (!isBusy) fields.clear() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (!isBusy) fields.clear() }
    DisposableEffect(fields) { onDispose { fields.clear() } }
    return fields
}
