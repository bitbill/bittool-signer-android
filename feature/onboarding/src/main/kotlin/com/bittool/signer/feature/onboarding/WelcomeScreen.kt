package com.bittool.signer.feature.onboarding

import android.content.pm.ApplicationInfo
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.brand.BrandTile
import com.bittool.signer.core.designsystem.component.BottomActionBar
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SecondaryButton
import com.bittool.signer.core.designsystem.component.SignerBottomSheet
import com.bittool.signer.core.designsystem.component.TextAction
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.bold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.ui.legal.LegalDocumentView
import com.bittool.signer.core.ui.logo.NetworkLogoStack

/**
 * The first screen when no wallet exists (iOS `WelcomeView`, Features/Onboarding/WelcomeView.swift:5-114; app-shell.md
 * §5): the brand hero, three promises, the supported networks, and the two ways in. [onStart] receives the Create /
 * Import intent; `:app` turns it into `AppRouter.startSetup` (the introduction runs first for the first wallet, and
 * the intent is kept through it). "Terms of Use" and "Privacy Policy" open the bundled texts in a sheet.
 *
 * It is a root stage: no back button, no tab bar. System Back leaves the app.
 */
@Composable
fun WelcomeScreen(onStart: (SetupIntent) -> Unit, modifier: Modifier = Modifier) {
    val viewModel: WelcomeViewModel = hiltViewModel()
    val legalSheet by viewModel.legalSheet.collectAsStateWithLifecycle()
    WelcomeContent(onStart = onStart, onShowLegal = viewModel::showLegal, modifier = modifier)
    legalSheet?.let { sheet -> LegalDocumentSheet(sheet = sheet, onDismiss = viewModel::dismissLegal) }
}

/**
 * Stateless Welcome (previews, screenshots): a scrolling column that fills at least the window, so on tall screens the
 * four gaps share the free height (iOS spacers with minimum lengths 32, 28, 28, 24) and on short ones it scrolls,
 * under an accent glow from the top; the pinned bar ends in the legal links.
 */
@Composable
fun WelcomeContent(
    onStart: (SetupIntent) -> Unit,
    onShowLegal: (LegalDocument) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SignerTheme.colors
    val scroll = rememberScrollState()
    Box(modifier.fillMaxSize().background(colors.page).testTag(WelcomeTags.ROOT)) {
        AccentGlow(colors.accent)
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewport = maxHeight
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .heightIn(min = viewport)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 28.dp)
                        .readableWidth(SignerMetrics.narrowWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Hero(Modifier.padding(top = 32.dp))
                    Features(Modifier.padding(top = 28.dp))
                    Networks(Modifier.padding(top = 28.dp, bottom = 24.dp))
                }
            }
            BottomActionBar(
                maxWidth = SignerMetrics.narrowWidth,
                bottomPadding = 0.dp,
                canScrollUnder = scroll.canScrollForward,
            ) {
                PrimaryButton(
                    text = stringResource(R.string.create_new_wallet),
                    icon = SignerIcons.Add,
                    onClick = { onStart(SetupIntent.Create) },
                    modifier = Modifier.testTag(WelcomeTags.CREATE),
                )
                SecondaryButton(
                    text = stringResource(R.string.import_existing_wallet),
                    icon = SignerIcons.Download,
                    onClick = { onStart(SetupIntent.Restore) },
                    modifier = Modifier.testTag(WelcomeTags.IMPORT),
                )
                // On top of the bar's own row spacing: the legal links are a separate thought from the two actions.
                LegalLinks(onShowLegal, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp))
            }
        }
    }
}

/** The radial accent wash behind the top of the page (decorative: hidden from accessibility). */
@Composable
private fun AccentGlow(accent: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .drawBehind {
                val radius = GlowRadius.toPx()
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0f)),
                        center = Offset(size.width / 2f, 0f),
                        radius = radius,
                    ),
                )
            },
    )
}

/** The brand tile (appearing with a soft spring unless animations are off), the name and the slogan. */
@Composable
private fun Hero(modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val reduceMotion = SignerTheme.reduceMotion
    val appear = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) appear.animateTo(1f, SignerMotion.hero())
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BrandTile(
            size = 96.dp,
            modifier = Modifier.graphicsLayer {
                val progress = appear.value
                alpha = progress
                scaleX = 0.9f + 0.1f * progress
                scaleY = 0.9f + 0.1f * progress
            },
        )
        Text(
            text = stringResource(R.string.bittool_signer),
            style = typography.largeTitle.bold(),
            color = colors.label,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.air_gapped_signing_for_everyone),
            style = typography.title3,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}

/** The three promises (WelcomeView.swift:76-84, 96-109); each row is read as one element. */
@Composable
private fun Features(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Feature(SignerIcons.DeviceLock, R.string.keys_stay_on_this_device, R.string.built_to_work_entirely_offline_no_accounts_no_cloud)
        Feature(SignerIcons.Code, R.string.fully_open_source_2, R.string.every_line_that_touches_your_keys_is_published_read_it)
        Feature(SignerIcons.ShieldCheck, R.string.know_what_you_sign, R.string.scan_a_request_see_it_decoded_and_matched_to_your)
    }
}

@Composable
private fun Feature(icon: ImageVector, title: Int, detail: Int) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Box(Modifier.width(34.dp), contentAlignment = Alignment.TopCenter) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.padding(top = 1.dp).size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = typography.headline, color = colors.label)
            Text(stringResource(detail), style = typography.subheadline, color = colors.secondaryLabel)
        }
    }
}

/** The bundled networks (the logo stack reads as one element with the short names) and their names in a line. */
@Composable
private fun Networks(modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NetworkLogoStack(size = 28.dp, ring = colors.page)
        Text(
            text = stringResource(R.string.bitcoin_ethereum_bnb_chain_arbitrum_base_tron_and_solana),
            style = SignerTheme.typography.footnote,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}

/** "Terms of Use · Privacy Policy": small tinted text buttons, each with a full touch target. */
@Composable
private fun LegalLinks(onShowLegal: (LegalDocument) -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LegalLink(stringResource(R.string.terms_of_use), WelcomeTags.TERMS) { onShowLegal(LegalDocument.Terms) }
        Text(stringResource(R.string.text), style = SignerTheme.typography.footnote, color = colors.tertiaryLabel, modifier = Modifier.clearAndSetSemantics {})
        LegalLink(stringResource(R.string.privacy_policy), WelcomeTags.PRIVACY) { onShowLegal(LegalDocument.Privacy) }
    }
}

@Composable
private fun LegalLink(text: String, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SignerTheme.typography.footnote, color = SignerTheme.colors.accent)
    }
}

/**
 * The bundled Terms of Use or Privacy Policy over Welcome (iOS `LegalDocumentSheet`): a full-height sheet with the
 * document's title and "Done" (`legal.done`), the text selectable and without links. English text by decision; the
 * title follows the app language like the button that opened it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun LegalDocumentSheet(sheet: LegalSheet, onDismiss: () -> Unit) {
    val colors = SignerTheme.colors
    val exposeTags = rememberExposesTestTags()
    SignerBottomSheet(onDismissRequest = onDismiss) {
        val sheetScope = this
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                // A sheet is its own window: the root's resource-id flag does not reach it (UI tests find `legal.done`).
                .semantics { testTagsAsResourceId = exposeTags },
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp)) {
                Text(
                    text = stringResource(if (sheet.document == LegalDocument.Terms) R.string.terms_of_use else R.string.privacy_policy),
                    style = SignerTheme.typography.headline,
                    color = colors.label,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 72.dp).semantics { heading() },
                )
                TextAction(
                    text = stringResource(R.string.done),
                    onClick = { sheetScope.dismiss() },
                    emphasized = true,
                    modifier = Modifier.align(Alignment.CenterEnd).testTag(WelcomeTags.LEGAL_DONE),
                )
            }
            LegalDocumentView(
                blocks = sheet.blocks.orEmpty(),
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(sheet.document.testTag),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            )
        }
    }
}

/** Debuggable builds expose test tags as view resource names, so UiAutomator finds them (gaps.md G1.0). */
@Composable
private fun rememberExposesTestTags(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
}

/** Test tags of Welcome: the iOS accessibility identifiers. */
object WelcomeTags {
    const val ROOT = "welcome"
    const val CREATE = "welcome.create"
    const val IMPORT = "welcome.import"
    const val TERMS = "welcome.terms"
    const val PRIVACY = "welcome.privacy"
    const val LEGAL_DONE = "legal.done"
}

private val GlowRadius = 420.dp
