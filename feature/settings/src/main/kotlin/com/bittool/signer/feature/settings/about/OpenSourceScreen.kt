package com.bittool.signer.feature.settings.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.common.Distribution
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.DetailLine
import com.bittool.signer.core.designsystem.component.GroupedSection
import com.bittool.signer.core.designsystem.component.NavRow
import com.bittool.signer.core.designsystem.component.TonalPillButton
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.ui.clipboard.SignerClipboard
import com.bittool.signer.core.ui.text.currentLocale
import com.bittool.signer.core.ui.text.formatInteger
import com.bittool.signer.core.ui.wallet.isolate
import com.bittool.signer.feature.settings.RowContentPadding
import com.bittool.signer.feature.settings.SectionFrame
import com.bittool.signer.feature.settings.SectionGap
import com.bittool.signer.feature.settings.SettingsPage
import com.bittool.signer.feature.settings.listContentPadding
import com.bittool.signer.feature.settings.rememberResumed

/** Test tags of the Open Source page (iOS identifiers plus Android-only ids). */
object OpenSourceTags {
    const val REPOSITORY = "openSource.repository"
    const val REPOSITORY_COPY = "openSource.repositoryCopy"
    const val ENGINE_REPOSITORY = "openSource.engineRepository"
    const val DIGEST = "openSource.digest"
    const val NOTICES = "openSource.notices"
    const val LIST = "openSource.list"
}

/** Open Source (inventory row 72). */
@Composable
fun OpenSourceScreen(
    onBack: () -> Unit,
    onOpenNotices: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OpenSourceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OpenSourceContent(state, onBack = rememberResumed(onBack), onOpenNotices = rememberResumed(onOpenNotices), modifier = modifier)
}

/**
 * The page in the iOS order — what is published, the repository, this build, how to rebuild the engine, the notices —
 * with the two Android additions of DECISIONS.md §9.1 / activity-settings.md §14.8: the engine's own repository (where
 * `signer.js` is built reproducibly) and the distribution and signing-certificate rows. Nothing opens a browser.
 */
@Composable
internal fun OpenSourceContent(state: OpenSourceUiState, onBack: () -> Unit, onOpenNotices: () -> Unit, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val reading = stringResource(R.string.reading)
    val unavailable = stringResource(R.string.unavailable)
    fun <T> Measured<T>.text(format: (T) -> String): String = when (this) {
        Measured.Reading -> reading
        Measured.Unavailable -> unavailable
        is Measured.Value -> format(value)
    }
    val resources = LocalResources.current
    val engineSize = state.engineBytes.text { resources.getString(R.string.ui_payload_bytes, formatInteger(it.toLong(), locale)) }
    val rows = listOf(
        "version" to (stringResource(R.string.app_version) to state.version),
        "engine" to (stringResource(R.string.signing_engine) to state.engineDigest.text { it }),
        "size" to (stringResource(R.string.engine_size) to engineSize),
        "registry" to (stringResource(R.string.token_registry) to state.registryVersion.text { it }),
        "networking" to (stringResource(R.string.networking_code) to stringResource(R.string.none)),
        "distribution" to (stringResource(R.string.settings_distribution) to distributionText(state.distribution)),
        "certificate" to (stringResource(R.string.settings_signing_certificate) to (state.signingCertificate ?: unavailable)),
    )
    val notices = stringResource(R.string.third_party_notices)
    val engineSourceLabel = stringResource(R.string.settings_engine_source)
    val steps = listOf(
        stringResource(R.string.clone_the_repository_and_check_out_the_tag_for_version_x, isolate(state.version)),
        stringResource(R.string.run_cd_engine_npm_ci_npm_run_build),
        stringResource(R.string.compare_the_sha_256_it_prints_and_engine_bundle),
    )
    SettingsPage(title = stringResource(R.string.open_source), onBack = onBack, modifier = modifier) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = insets.calculateTopPadding()).testTag(OpenSourceTags.LIST),
            contentPadding = listContentPadding(insets),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            item(key = "intro") { Callout(stringResource(R.string.settings_open_source_intro), SectionFrame, tone = Tone.Info) }
            item(key = "repository") {
                GroupedSection(
                    modifier = SectionFrame,
                    header = stringResource(R.string.repository),
                    footer = stringResource(R.string.the_app_never_opens_a_browser_copy_the_address_and_open),
                ) {
                    item { RepositoryBlock(label = null, url = state.repository, tag = OpenSourceTags.REPOSITORY, copyTag = OpenSourceTags.REPOSITORY_COPY) }
                }
            }
            item(key = "build") {
                GroupedSection(
                    modifier = SectionFrame,
                    header = stringResource(R.string.this_build),
                    footer = stringResource(R.string.the_engine_digest_is_the_sha_256_of_signer_js_as_it_is),
                ) {
                    rows.forEach { (id, row) ->
                        item(key = id) {
                            DetailLine(
                                label = row.first,
                                value = row.second,
                                modifier = Modifier
                                    .padding(RowContentPadding)
                                    .then(if (id == "engine") Modifier.testTag(OpenSourceTags.DIGEST) else Modifier),
                                // The distribution is prose; the others are values the app did not write (iOS `value:`).
                                monospaced = id != "distribution",
                            )
                        }
                    }
                }
            }
            item(key = "rebuild") {
                GroupedSection(
                    modifier = SectionFrame,
                    header = stringResource(R.string.rebuild_it_yourself),
                    footer = stringResource(R.string.the_engine_bundle_is_deterministic_the_same_sources),
                ) {
                    item(key = "source") {
                        RepositoryBlock(label = engineSourceLabel, url = state.repository, tag = OpenSourceTags.ENGINE_REPOSITORY, copyTag = null)
                    }
                    item(key = "steps") { RebuildSteps(steps) }
                }
            }
            item(key = "notices") {
                GroupedSection(modifier = SectionFrame, footer = stringResource(R.string.the_engine_bundles_open_source_libraries_for)) {
                    item { NavRow(notices, null, onOpenNotices, modifier = Modifier.testTag(OpenSourceTags.NOTICES)) }
                }
            }
        }
    }
}

/**
 * A repository address (callout monospaced, selectable, left to right) with the bordered "Copy Address" button that
 * turns into "Copied" and stays (iOS `OpenSourceView`, `SettingsTab.swift:236-251`).
 */
@Composable
private fun RepositoryBlock(label: String?, url: String, tag: String, copyTag: String?) {
    val context = LocalContext.current
    var copied by rememberSaveable(url) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(RowContentPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Text(label, style = SignerTheme.typography.caption.semibold(), color = SignerTheme.colors.secondaryLabel)
        SelectionContainer {
            Text(
                text = url,
                style = SignerTheme.typography.callout.monospaced().ltr(),
                color = SignerTheme.colors.label,
                modifier = Modifier.testTag(tag),
            )
        }
        TonalPillButton(
            text = stringResource(if (copied) R.string.copied else R.string.copy_address),
            icon = if (copied) SignerIcons.Check else SignerIcons.Copy,
            onClick = { if (SignerClipboard.copy(context, url)) copied = true },
            modifier = if (copyTag != null) Modifier.testTag(copyTag) else Modifier,
        )
    }
}

/** "1." "2." "3." (footnote monospaced, secondary) beside each step (footnote), first baselines aligned. */
@Composable
private fun RebuildSteps(steps: List<String>) {
    Column(Modifier.fillMaxWidth().padding(RowContentPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(
                    text = stringResource(R.string.x_3, index + 1),
                    style = SignerTheme.typography.footnote.monospaced(),
                    color = SignerTheme.colors.secondaryLabel,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(step, style = SignerTheme.typography.footnote, color = SignerTheme.colors.label, modifier = Modifier.weight(1f).alignByBaseline())
            }
        }
    }
}

@Composable
private fun distributionText(distribution: Distribution): String = stringResource(
    when (distribution) {
        Distribution.DirectApk -> R.string.settings_distribution_apk
        Distribution.GooglePlay -> R.string.settings_distribution_play
    },
)
