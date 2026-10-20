package com.bittool.signer.feature.settings.language

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.GroupedSection
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.contentDirection
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AppLanguage
import com.bittool.signer.core.ui.text.currentLocale
import com.bittool.signer.feature.settings.SectionFrame
import com.bittool.signer.feature.settings.SettingsPage
import com.bittool.signer.feature.settings.listContentPadding
import com.bittool.signer.feature.settings.rememberResumed
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The language page state: every option in picker order and the applied one. */
@Immutable
data class LanguageUiState(
    val selected: AppLanguage,
    /** The device's first language ("System" follows it). */
    val systemLocale: Locale,
    val options: List<AppLanguage> = AppLanguage.entries,
)

/**
 * Settings › Language (iOS `Picker` over `AppLanguage.selectable`, activity-settings.md §3.5, §14.5): "System" and the
 * 21 bundled languages by their own names. Choosing one applies it at once through AppCompat's per-app locales; the
 * activity is recreated in the new language (Arabic right to left, Latin digits) and the person stays on this page.
 */
@HiltViewModel
class LanguageViewModel @Inject constructor(private val controller: LanguageController) : ViewModel() {
    private val state = MutableStateFlow(LanguageUiState(controller.current(), controller.systemLocale()))
    val uiState: StateFlow<LanguageUiState> = state.asStateFlow()

    fun onSelect(language: AppLanguage) {
        if (language == state.value.selected) return
        state.value = state.value.copy(selected = language)
        controller.apply(language)
    }

    /** Re-reads the applied language (after a recreation, or a change in Android's "App languages"). */
    fun refresh() {
        state.value = state.value.copy(selected = controller.current(), systemLocale = controller.systemLocale())
    }
}

/** Test tag of one language row (`language.<iOS raw value>`). */
fun languageTag(language: AppLanguage) = "language.${language.code}"

@Composable
fun LanguageScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: LanguageViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.refresh()
        onStopOrDispose { }
    }
    LanguageContent(state, onSelect = viewModel::onSelect, onBack = rememberResumed(onBack), modifier = modifier)
}

@Composable
internal fun LanguageContent(state: LanguageUiState, onSelect: (AppLanguage) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val appLocale = currentLocale()
    SettingsPage(title = stringResource(R.string.language), onBack = onBack, modifier = modifier) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = insets.calculateTopPadding()),
            contentPadding = listContentPadding(insets),
        ) {
            item(key = "languages") {
                val systemName = stringResource(R.string.system)
                val rows = state.options.map { option ->
                    val title = if (option == AppLanguage.System) AnnotatedString(systemName) else endonym(option)
                    val detail = if (option == AppLanguage.System) {
                        state.systemLocale.getDisplayName(appLocale)
                    } else {
                        Locale.forLanguageTag(option.languageTag).getDisplayName(appLocale).takeIf { it != option.endonym }
                    }
                    Triple(option, title, detail)
                }
                GroupedSection(
                    modifier = SectionFrame.selectableGroup(),
                    footer = stringResource(R.string.the_interface_language_what_a_signing_request_itself),
                ) {
                    rows.forEach { (option, title, detail) ->
                        item(key = option.code) {
                            LanguageRow(title, detail, selected = option == state.selected, onClick = { onSelect(option) }, tag = languageTag(option))
                        }
                    }
                }
            }
        }
    }
}

/** The endonym tagged with its language, so TalkBack pronounces "Deutsch" in German. */
@Composable
private fun endonym(language: AppLanguage): AnnotatedString = remember(language) {
    buildAnnotatedString {
        withStyle(SpanStyle(localeList = LocaleList(language.languageTag.substringBefore("-u-")))) { append(language.endonym) }
    }
}

@Composable
private fun LanguageRow(title: AnnotatedString, detail: String?, selected: Boolean, onClick: () -> Unit, tag: String) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, indication = ripple(), interactionSource = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = typography.body.contentDirection(), color = colors.label)
            if (detail != null) Text(detail, style = typography.footnote, color = colors.secondaryLabel)
        }
        if (selected) Icon(SignerIcons.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
    }
}
