package com.bittool.signer.feature.wallets.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bittool.signer.core.designsystem.component.NavIcon
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.component.StatusToastHost
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.haptics.rememberSignerHaptics
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.ui.password.wipe
import com.bittool.signer.core.ui.text.Callout

/**
 * The frame of the wallet security pages (iOS sheets with their own bar and a leading "Cancel"; modal routes on
 * Android, ARCHITECTURE §6 item 7): an inline title, the close button, a scrolling form that stays above the keyboard,
 * and the page's toast. While [busy] neither the close button nor Back leaves (iOS `interactiveDismissDisabled`);
 * otherwise the close button runs [onCancel] (cancel, wipe, pop) and Back pops with the predictive animation.
 *
 * The close button is the cross of every modal page, read as the iOS "Cancel" (`WalletSecuritySheets.swift:169, 248,
 * 327`) like the Recovery Phrase page's (QA wallets-10): it discards the form. Its e2e tag stays `nav.close`.
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
                navigationLabel = stringResource(R.string.cancel),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                // The bar insets already in [padding] are consumed first, so the keyboard adds only what it covers
                // beyond them (the navigation bar is not counted twice while the keyboard is up).
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(padding)
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

/**
 * The page's failure (iOS `Callout(verbatim: error, tone: .danger)` of the security sheets: "The password is
 * incorrect.", the vault back-off, "Enter this wallet’s password."): engine and vault text verbatim, announced by
 * TalkBack as it appears (the person's focus is still on the button they tapped) and felt with the reject haptic
 * (ARCHITECTURE §4.5, wrong password). A repeated failure is a new error: the operation clears the old one first.
 */
@Composable
internal fun SecurityError(error: UiText?, modifier: Modifier = Modifier) {
    if (error == null) return
    val haptics = rememberSignerHaptics()
    LaunchedEffect(error) { haptics.reject() }
    Callout(
        text = error,
        tone = Tone.Danger,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
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
 * [clear] empties every field, undo history included.
 */
@Stable
internal class PasswordFields(count: Int) {
    val fields: List<TextFieldState> = List(count) { TextFieldState() }

    operator fun get(index: Int): TextFieldState = fields[index]

    fun clear() = fields.forEach { it.wipe() }
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
