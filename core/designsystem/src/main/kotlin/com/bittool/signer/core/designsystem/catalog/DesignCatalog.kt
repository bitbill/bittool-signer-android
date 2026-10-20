package com.bittool.signer.core.designsystem.catalog

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.R as DesignR
import com.bittool.signer.core.designsystem.brand.BrandMark
import com.bittool.signer.core.designsystem.brand.BrandTile
import com.bittool.signer.core.designsystem.component.BottomActionBar
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.DetailLine
import com.bittool.signer.core.designsystem.component.EmptyState
import com.bittool.signer.core.designsystem.component.GroupedSection
import com.bittool.signer.core.designsystem.component.IconRowDividerInset
import com.bittool.signer.core.designsystem.component.NavRow
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SecondaryButton
import com.bittool.signer.core.designsystem.component.SegmentedControl
import com.bittool.signer.core.designsystem.component.SignerCard
import com.bittool.signer.core.designsystem.component.StatusPill
import com.bittool.signer.core.designsystem.component.StepRow
import com.bittool.signer.core.designsystem.component.SwitchRow
import com.bittool.signer.core.designsystem.component.SymbolHero
import com.bittool.signer.core.designsystem.component.TextAction
import com.bittool.signer.core.designsystem.component.ToastCapsule
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.component.TonalPillButton
import com.bittool.signer.core.designsystem.component.ValueRow
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.l10n.R

/**
 * One block of the design catalog: a developer label and the components it shows. The catalog is a QA surface reached
 * from debug builds only (and from the Roborazzi screenshot tests), so [title] is a developer label, not a localised
 * string; every component inside shows real catalogue strings, so translations and RTL are exercised.
 */
@Immutable
class CatalogSection(val title: String, val content: @Composable () -> Unit)

/** The `:core:designsystem` part of the design catalog, in reading order. `:core:ui` appends its own sections. */
val DesignCatalogSections: List<CatalogSection> = listOf(
    CatalogSection("Brand") { BrandSection() },
    CatalogSection("Colours") { ColourSection() },
    CatalogSection("Typography") { TypographySection() },
    CatalogSection("Buttons") { ButtonSection() },
    CatalogSection("Callouts and pills") { CalloutSection() },
    CatalogSection("Heroes and steps") { HeroSection() },
    CatalogSection("Segmented control") { SegmentSection() },
    CatalogSection("Grouped list") { GroupedSectionSample() },
    CatalogSection("Empty state and toast") { EmptySection() },
    CatalogSection("Bottom action bar") { BottomBarSection() },
    CatalogSection("Chain artwork") { ChainArtworkSection() },
    CatalogSection("Icons") { IconSection() },
)

/** A section's label as the catalog draws it. */
@Composable
fun CatalogSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = SignerTheme.typography.footnote.monospaced(),
        color = SignerTheme.colors.secondaryLabel,
        modifier = modifier
            .padding(horizontal = SignerMetrics.pagePadding, vertical = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun Padded(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = SignerMetrics.pagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun BrandSection() = Padded {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandTile(size = 96.dp, shadow = false)
        BrandTile(size = 88.dp)
        BrandTile(size = 44.dp, shadow = false)
        Box(Modifier.size(56.dp).background(SignerTheme.colors.brandInk, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            BrandMark(Modifier.size(40.dp))
        }
    }
    Text(stringResource(R.string.air_gapped_signing_for_everyone), style = SignerTheme.typography.footnote, color = SignerTheme.colors.secondaryLabel)
}

@Composable
private fun ColourSection() = Padded {
    val c = SignerTheme.colors
    val swatches = listOf(
        "page" to c.page, "card" to c.card, "raised" to c.raised, "accent" to c.accent, "accentOnTint" to c.accentOnTint,
        "onAccent" to c.onAccent, "label" to c.label, "secondary" to c.secondaryLabel, "tertiary" to c.tertiaryLabel,
        "separator" to c.opaqueSeparator, "fill" to c.fill, "success" to c.success, "warning" to c.warning,
        "danger" to c.danger, "successOnTint" to c.successOnTint, "warningOnTint" to c.warningOnTint,
        "dangerOnTint" to c.dangerOnTint, "strengthMid" to c.strengthMid, "mint" to c.mint, "brandInk" to c.brandInk,
    )
    ColourGrid(swatches)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColourGrid(swatches: List<Pair<String, Color>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        swatches.forEach { (name, color) ->
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(SignerTheme.colors.opaqueSeparator, RoundedCornerShape(10.dp))
                        .padding(0.5.dp)
                        .background(color, RoundedCornerShape(10.dp)),
                )
                Text(name, style = SignerTheme.typography.caption2, color = SignerTheme.colors.secondaryLabel, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TypographySection() = Padded {
    val t = SignerTheme.typography
    val sample = stringResource(R.string.how_signing_works)
    listOf<Pair<String, TextStyle>>(
        "largeTitle" to t.largeTitle, "title2" to t.title2, "title3" to t.title3, "headline" to t.headline,
        "body" to t.body, "callout" to t.callout, "subheadline" to t.subheadline, "footnote" to t.footnote,
        "caption" to t.caption, "caption2" to t.caption2,
    ).forEach { (name, style) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = t.caption2.monospaced(), color = SignerTheme.colors.tertiaryLabel, modifier = Modifier.width(84.dp))
            Text(sample, style = style, color = SignerTheme.colors.label)
        }
    }
    Text("0x9858 EfFD 232B 4033 E47d 9000 3D41 EC34 EcaE da94", style = t.callout.monospaced().ltr(), color = SignerTheme.colors.label)
}

@Composable
private fun ButtonSection() = Padded {
    PrimaryButton(stringResource(R.string.create_new_wallet), onClick = {}, icon = SignerIcons.Add)
    PrimaryButton(stringResource(R.string.sign_with_x, "Main"), onClick = {}, icon = SignerIcons.Signature, busy = true)
    PrimaryButton(stringResource(R.string.continue_text), onClick = {}, enabled = false)
    SecondaryButton(stringResource(R.string.import_recovery_phrase), onClick = {}, icon = SignerIcons.Key)
    SecondaryButton(stringResource(R.string.cannot_sign), onClick = {}, icon = SignerIcons.CancelCircle, enabled = false)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TonalPillButton(stringResource(R.string.pair), SignerIcons.QrCode, onClick = {})
        TonalPillButton(stringResource(R.string.addresses), SignerIcons.ListAlt, onClick = {})
    }
    Row {
        TextAction(stringResource(R.string.cancel), onClick = {})
        TextAction(stringResource(R.string.done), onClick = {}, emphasized = true)
        TextAction(stringResource(R.string.reject), onClick = {}, destructive = true)
    }
}

@Composable
private fun CalloutSection() = Padded {
    Callout(stringResource(R.string.your_watch_only_wallet_shows_the_unsigned_request_as_a), tone = Tone.Info)
    Callout(stringResource(R.string.signature_created), tone = Tone.Success)
    // Engine warnings are shown verbatim; this one stands in for an engine-authored sample.
    Callout("UNLIMITED approval: the spender can move every token of this contract.", tone = Tone.Danger, verbatim = true)
    Callout(
        text = stringResource(R.string.your_encrypted_wallet_files_are_unchanged_unlock_this),
        title = stringResource(R.string.wallets_unavailable),
        tone = Tone.Warning,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill(stringResource(R.string.signed), SignerIcons.Check, SignerTheme.colors.success)
        StatusPill(stringResource(R.string.high_risk), SignerIcons.ShieldAlert, SignerTheme.colors.danger)
    }
    StatusPill(stringResource(R.string.partially_decoded), SignerIcons.WarningFilled, SignerTheme.colors.warning)
    DetailLine(stringResource(R.string.request_fingerprint), "3F2A 91C4 0B77 D2E8 5A10 C3F9 8E44 01AB")
    DetailLine(stringResource(R.string.version), "1.0.0 (1)", monospaced = false)
}

@Composable
private fun HeroSection() = Padded {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SymbolHero(SignerIcons.VerifiedFilled, size = 44.dp, color = SignerTheme.colors.success)
        SymbolHero(SignerIcons.QrCodeScanner, size = 44.dp)
    }
    SignerCard {
        Text(stringResource(R.string.how_signing_works), style = SignerTheme.typography.headline)
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StepRow(1, stringResource(R.string.scan), stringResource(R.string.your_watch_only_wallet_shows_the_unsigned_request_as_a), SignerIcons.QrCodeScanner)
            StepRow(2, stringResource(R.string.review), stringResource(R.string.bittool_signer_decodes_it_and_finds_the_matching_wallet), SignerIcons.ManageSearch)
            StepRow(3, stringResource(R.string.authorize), stringResource(R.string.enter_that_wallets_password_to_sign), SignerIcons.Lock)
            StepRow(4, stringResource(R.string.return_text), stringResource(R.string.scan_the_signature_back_your_other_wallet_broadcasts_it), SignerIcons.Undo)
        }
    }
}

@Composable
private fun SegmentSection() = Padded {
    var level by remember { mutableIntStateOf(0) }
    SegmentedControl(listOf(stringResource(R.string.wallet_level), stringResource(R.string.coin_level)), level, { level = it })
    var format by remember { mutableIntStateOf(1) }
    SegmentedControl(listOf(stringResource(R.string.words), stringResource(R.string.qr_code)), format, { format = it })
    var tab by remember { mutableIntStateOf(0) }
    SegmentedControl(listOf(stringResource(R.string.formats), stringResource(R.string.wallets)), tab, { tab = it })
}

@Composable
private fun GroupedSectionSample() = Padded {
    var lock by remember { mutableStateOf(true) }
    GroupedSection(header = stringResource(R.string.settings), footer = stringResource(R.string.air_gapped_signing_for_everyone)) {
        item(dividerInset = IconRowDividerInset) {
            SwitchRow(stringResource(R.string.wallet_password), SignerIcons.ShieldLock, lock, { lock = it })
        }
        item(dividerInset = IconRowDividerInset) {
            NavRow(stringResource(R.string.theme), SignerIcons.Contrast, onClick = {}, value = stringResource(R.string.system))
        }
        item(dividerInset = IconRowDividerInset) { NavRow(stringResource(R.string.language), SignerIcons.Language, onClick = {}) }
        item { ValueRow(stringResource(R.string.version), "1.0.0 (1)") }
    }
    GroupedSection {
        item { NavRow(stringResource(R.string.delete_wallet), SignerIcons.Delete, onClick = {}, destructive = true) }
    }
}

@Composable
private fun EmptySection() = Padded {
    EmptyState(
        icon = SignerIcons.Signature,
        title = stringResource(R.string.no_signatures_yet),
        description = stringResource(R.string.signatures_you_create_on_this_device_are_listed_here),
    )
    EmptyState(
        icon = SignerIcons.StorageAlert,
        title = stringResource(R.string.wallets_unavailable),
        description = stringResource(R.string.your_encrypted_wallet_files_are_unchanged_unlock_this),
        action = {
            PrimaryButton(stringResource(R.string.try_again), onClick = {}, icon = SignerIcons.Refresh, fillWidth = false, modifier = Modifier.width(280.dp))
        },
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ToastCapsule(stringResource(R.string.copied)) }
}

@Composable
private fun BottomBarSection() {
    BottomActionBar(canScrollUnder = true) {
        Text(stringResource(R.string.signature_created), style = SignerTheme.typography.footnote, color = SignerTheme.colors.secondaryLabel)
        PrimaryButton(stringResource(R.string.done), onClick = {})
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainArtworkSection() = Padded {
    val logos = listOf(
        DesignR.drawable.ic_chain_bitcoin, DesignR.drawable.ic_chain_ethereum, DesignR.drawable.ic_chain_bnb,
        DesignR.drawable.ic_chain_arbitrum, DesignR.drawable.ic_chain_base, DesignR.drawable.ic_chain_tron,
        DesignR.drawable.ic_chain_solana,
    )
    listOf(16.dp, 28.dp, 60.dp).forEach { size ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            logos.forEach { Image(painterResource(it), contentDescription = null, modifier = Modifier.size(size)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconSection() = Padded {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        catalogIcons().forEach { Icon(it, contentDescription = null, tint = SignerTheme.colors.accent, modifier = Modifier.size(24.dp)) }
    }
}

private fun catalogIcons(): List<ImageVector> = with(SignerIcons) {
    listOf(
        InfoFilled, CheckCircleFilled, WarningFilled, ReportFilled, DangerousFilled, VerifiedFilled, HelpFilled,
        ShieldAlert, ShieldCheck, ShieldLock, Visibility, VisibilityOff, Copy, Check, Play, Pause, ChevronRight,
        UnfoldMore, Circle, CheckBoxFilled, CheckBoxBlank, Hub, PhotoFrame, Stack, Tag, QrCodeScanner, QrCode, Wallet,
        History, Settings, Signature, Lock, LockOpen, LockReset, Key, Camera, FlashlightOn, Close, Add, Remove,
        SaveFile, Export, Refresh, Paste, ManageSearch, Undo, ListAlt, Build, Edit, Delete, Contrast, Language, Token,
        Mail, GridView, Code, Flight, WifiOff, BackHand, SwapHoriz, StorageAlert, AppFill, ArrowOutward, ArrowInward,
        Branch, Merge, Cycle, Columns, Chart, ArrowBack,
    )
}

/** Every catalog section stacked, on the page colour (previews and the debug catalog screen). */
@Composable
fun DesignCatalogColumn(sections: List<CatalogSection>, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().background(SignerTheme.colors.page).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CatalogSectionHeader(section.title)
                section.content()
            }
        }
    }
}

@Preview(name = "Design catalog · light", heightDp = 3200)
@Composable
private fun DesignCatalogLightPreview() {
    SignerTheme(darkTheme = false, applySystemBarAppearance = false) { DesignCatalogColumn(DesignCatalogSections) }
}

@Preview(name = "Design catalog · dark", heightDp = 3200)
@Composable
private fun DesignCatalogDarkPreview() {
    SignerTheme(darkTheme = true, applySystemBarAppearance = false) { DesignCatalogColumn(DesignCatalogSections) }
}
