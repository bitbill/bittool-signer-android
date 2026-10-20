package com.bittool.signer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import com.bittool.signer.core.designsystem.brand.BrandTile
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.GroupedSection
import com.bittool.signer.core.designsystem.component.IconRowDividerInset
import com.bittool.signer.core.designsystem.component.NavRow
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.component.StatusToastHost
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.component.SwitchRow
import com.bittool.signer.core.designsystem.component.ValueRow
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.haptics.HapticKind
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.contentDirection
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AppLanguage
import com.bittool.signer.core.model.Appearance
import com.bittool.signer.core.navigation.CompatibilityRoute
import com.bittool.signer.core.navigation.ContactRoute
import com.bittool.signer.core.navigation.LanguageRoute
import com.bittool.signer.core.navigation.LegalDocumentRoute
import com.bittool.signer.core.navigation.NetworksRoute
import com.bittool.signer.core.navigation.OpenSourceRoute
import com.bittool.signer.core.navigation.SignerRoute
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.ui.text.asString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Test tags of the Settings root (iOS accessibility identifiers, gaps.md G1.18). */
object SettingsTags {
    const val APP_LOCK = "settings.appLock"
    const val APPEARANCE = "settings.appearance"
    const val LANGUAGE = "settings.language"
    const val NETWORKS = "settings.networks"
    const val COMPATIBILITY = "settings.compatibility"
    const val OPEN_SOURCE = "settings.openSource"
    const val CONTACT = "settings.contact"
    const val TERMS = "settings.terms"
    const val PRIVACY = "settings.privacy"
    const val LIST = "settings.list"

    fun theme(appearance: Appearance) = "settings.appearance.${appearance.wire}"
}

/** The Settings tab root (iOS `SettingsTab`, inventory rows 62–64). [onNavigate] pushes a Settings page. */
@Composable
fun SettingsScreen(
    onNavigate: (SignerRoute) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scrollToTop = remember(viewModel, lifecycle) { viewModel.scrollToTop.flowWithLifecycle(lifecycle) }
    LifecycleStartEffect(viewModel) {
        viewModel.refreshLanguage()
        onStopOrDispose { }
    }
    SettingsContent(
        state = state,
        onAppLockChange = viewModel::onAppLockChange,
        onThemeClick = viewModel::onThemeClicked,
        onThemeSelected = viewModel::onAppearanceSelected,
        onThemeDismissed = viewModel::onThemeDismissed,
        onNavigate = rememberResumedAction(onNavigate),
        modifier = modifier,
        scrollToTop = scrollToTop,
    )
}

/**
 * Stateless Settings root (previews, screenshots and Compose tests). Each [scrollToTop] emission (tab reselection)
 * scrolls back to the top and expands the large title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    onAppLockChange: (Boolean) -> Unit,
    onThemeClick: () -> Unit,
    onThemeSelected: (Appearance) -> Unit,
    onThemeDismissed: () -> Unit,
    onNavigate: (SignerRoute) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    scrollToTop: Flow<Unit> = emptyFlow(),
) {
    val toast = rememberStatusToastState()
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(scrollToTop, listState, bar) { scrollToTop.collect { scrollToTopAndExpand(listState, bar) } }
    Box(modifier.fillMaxSize()) {
        SettingsPage(title = stringResource(R.string.settings), scrollBehavior = bar) { insets ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = insets.calculateTopPadding())
                    .testTag(SettingsTags.LIST),
                contentPadding = listContentPadding(insets),
                verticalArrangement = Arrangement.spacedBy(SectionGap),
            ) {
                item(key = "security") { SecuritySection(state, onAppLockChange, toast) }
                item(key = "appearance") { AppearanceSection(state, onThemeClick, onLanguageClick = { onNavigate(LanguageRoute) }) }
                item(key = "reference") { ReferenceSection(onNavigate) }
                item(key = "about") { AboutSection(state.version, onNavigate) }
                item(key = "brand") { BrandFooter() }
            }
        }
        StatusToastHost(toast)
    }
    if (state.showsThemeDialog) ThemeDialog(state.appearance, onThemeSelected, onThemeDismissed)
}

/** "Security": the App Lock switch (G7.4 wording), its error and the availability footer. */
@Composable
private fun SecuritySection(state: SettingsUiState, onAppLockChange: (Boolean) -> Unit, toast: StatusToastState) {
    val title = stringResource(R.string.security_app_lock)
    val overlayBlocked = stringResource(R.string.security_overlay_blocked)
    val error = state.appLockError?.asString()
    GroupedSection(
        modifier = SectionFrame,
        header = stringResource(R.string.security),
        footer = stringResource(
            if (state.biometricsAvailable) R.string.security_app_lock_footer_available else R.string.security_app_lock_footer_unavailable,
        ),
    ) {
        item(key = "appLock", dividerInset = IconRowDividerInset) {
            SwitchRow(
                title = title,
                icon = SignerIcons.Fingerprint,
                checked = state.appLockEnabled,
                onCheckedChange = onAppLockChange,
                enabled = state.appLockToggleEnabled,
                modifier = Modifier
                    .obscuredTouchGuard { toast.show(overlayBlocked, SignerIcons.WarningFilled, HapticKind.Warning) }
                    .testTag(SettingsTags.APP_LOCK),
            )
        }
        if (error != null) {
            item(key = "appLockError") {
                Text(
                    text = error,
                    style = SignerTheme.typography.footnote.contentDirection(),
                    color = SignerTheme.colors.dangerOnTint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(RowContentPadding)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** "Appearance": Theme (a single-choice dialog) and Language (its own page); the footer explains what changes. */
@Composable
private fun AppearanceSection(state: SettingsUiState, onThemeClick: () -> Unit, onLanguageClick: () -> Unit) {
    val theme = stringResource(R.string.theme)
    val themeValue = appearanceLabel(state.appearance)
    val language = stringResource(R.string.language)
    val languageValue = languageLabel(state.language)
    GroupedSection(
        modifier = SectionFrame,
        header = stringResource(R.string.appearance),
        footer = stringResource(R.string.the_interface_language_what_a_signing_request_itself),
    ) {
        // iOS menu pickers in a Form: the current choice against the chevron, in its own direction (endonyms).
        item(key = "theme", dividerInset = IconRowDividerInset) {
            NavRow(
                theme, SignerIcons.Contrast, onThemeClick, Modifier.testTag(SettingsTags.APPEARANCE),
                value = themeValue, verbatimValue = true,
            )
        }
        item(key = "language") {
            NavRow(
                language, SignerIcons.Language, onLanguageClick, Modifier.testTag(SettingsTags.LANGUAGE),
                value = languageValue, verbatimValue = true,
            )
        }
    }
}

@Composable
private fun ReferenceSection(onNavigate: (SignerRoute) -> Unit) {
    val networks = stringResource(R.string.networks_tokens_contracts)
    val compatibility = stringResource(R.string.compatibility)
    GroupedSection(modifier = SectionFrame, header = stringResource(R.string.reference)) {
        item(key = "networks", dividerInset = IconRowDividerInset) {
            NavRow(networks, SignerIcons.Token, { onNavigate(NetworksRoute) }, modifier = Modifier.testTag(SettingsTags.NETWORKS))
        }
        item(key = "compatibility") {
            NavRow(compatibility, SignerIcons.ShieldCheck, { onNavigate(CompatibilityRoute) }, modifier = Modifier.testTag(SettingsTags.COMPATIBILITY))
        }
    }
}

/** "About": version, "Network connections: None", and the pages about the app (row text in the body colour). */
@Composable
private fun AboutSection(version: String, onNavigate: (SignerRoute) -> Unit) {
    val versionLabel = stringResource(R.string.version)
    val connections = stringResource(R.string.network_connections)
    val none = stringResource(R.string.none)
    val openSource = stringResource(R.string.fully_open_source)
    val contact = stringResource(R.string.contact_us)
    val terms = stringResource(R.string.terms_of_use)
    val privacy = stringResource(R.string.privacy_policy)
    GroupedSection(modifier = SectionFrame, header = stringResource(R.string.about)) {
        item(key = "version") { ValueRow(versionLabel, version, verbatim = true) }
        item(key = "connections") { ValueRow(connections, none, verbatim = true) }
        item(key = "openSource") {
            NavRow(openSource, null, { onNavigate(OpenSourceRoute) }, modifier = Modifier.testTag(SettingsTags.OPEN_SOURCE))
        }
        item(key = "contact") { NavRow(contact, null, { onNavigate(ContactRoute) }, modifier = Modifier.testTag(SettingsTags.CONTACT)) }
        item(key = "terms") {
            NavRow(terms, null, { onNavigate(LegalDocumentRoute(LegalDocument.Terms)) }, modifier = Modifier.testTag(SettingsTags.TERMS))
        }
        item(key = "privacy") {
            NavRow(privacy, null, { onNavigate(LegalDocumentRoute(LegalDocument.Privacy)) }, modifier = Modifier.testTag(SettingsTags.PRIVACY))
        }
    }
}

/** The About footer (iOS `BrandTile(44, shadow: false)` + "Air-gapped signing. For everyone."). */
@Composable
private fun BrandFooter() {
    Column(
        modifier = SectionFrame.padding(top = 4.dp, bottom = 8.dp).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BrandTile(size = 44.dp, shadow = false)
        Text(
            text = stringResource(R.string.air_gapped_signing_for_everyone),
            style = SignerTheme.typography.footnote,
            color = SignerTheme.colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}

/** Theme picker (iOS menu `Picker`, inventory row 64): System / Light / Dark, applied on selection. */
@Composable
private fun ThemeDialog(selected: Appearance, onSelect: (Appearance) -> Unit, onDismiss: () -> Unit) {
    SignerAlertDialog(
        title = stringResource(R.string.theme),
        text = null,
        confirm = DialogAction(text = stringResource(R.string.cancel), onClick = onDismiss),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            Appearance.entries.forEach { option ->
                val isSelected = option == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                        .testTag(SettingsTags.theme(option)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = SignerTheme.colors.accent,
                            unselectedColor = SignerTheme.colors.tertiaryLabel,
                        ),
                    )
                    Text(appearanceLabel(option), style = SignerTheme.typography.body, color = SignerTheme.colors.label)
                }
            }
        }
    }
}

@Composable
internal fun appearanceLabel(appearance: Appearance): String = stringResource(
    when (appearance) {
        Appearance.System -> R.string.system
        Appearance.Light -> R.string.light
        Appearance.Dark -> R.string.dark
    },
)

/** "System" (translated) or the language's own name (never translated). */
@Composable
internal fun languageLabel(language: AppLanguage): String =
    if (language == AppLanguage.System) stringResource(R.string.system) else language.endonym
