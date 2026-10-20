package com.bittool.signer.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.l10n.R

/**
 * One page of the three-page introduction (iOS `OnboardingView.Page`, Features/Onboarding/WelcomeView.swift:122-157;
 * app-shell.md §6.2). Texts are resources so they resolve at draw time in the app language.
 */
@Immutable
internal data class OnboardingPage(
    val hero: ImageVector,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val points: List<OnboardingPoint>,
)

/**
 * A key point of a page. iOS keys a point by its SF Symbol ("the SF Symbol is the identity, so the text can be
 * translated without changing what `ForEach` tracks"); [id] is that symbol name, the stable key of the row.
 */
@Immutable
internal data class OnboardingPoint(val id: String, val icon: ImageVector, @StringRes val text: Int)

/**
 * The introduction, in order. Pages 1 and 2 are the iOS text; page 3 ("Prepare Your Device", the air-gapped device
 * guide, activity-settings.md §11) uses the Android wording: "phone" instead of "iPhone", and the iOS-only point about
 * the China-region wireless-data prompt is replaced by the fact Android enforces (no internet permission).
 */
internal val OnboardingPages: List<OnboardingPage> = listOf(
    OnboardingPage(
        hero = SignerIcons.DeviceLock,
        title = R.string.offline_by_design,
        body = R.string.your_keys_are_created_encrypted_and_used_only_on_this,
        points = listOf(
            OnboardingPoint("key.fill", SignerIcons.Key, R.string.recovery_phrases_never_leave_the_app),
            OnboardingPoint("wifi.slash", SignerIcons.WifiOff, R.string.no_accounts_servers_or_cloud_sync),
            OnboardingPoint("hand.raised.fill", SignerIcons.BackHand, R.string.every_signature_needs_your_wallet_password),
        ),
    ),
    OnboardingPage(
        hero = SignerIcons.QrCodeScanner,
        title = R.string.built_for_compatibility,
        body = R.string.pair_bittool_signer_with_a_watch_only_wallet_on_another,
        points = listOf(
            OnboardingPoint("arrow.left.arrow.right", SignerIcons.SwapHoriz, R.string.open_bc_ur_and_psbt_qr_standards),
            OnboardingPoint("square.stack.3d.up.fill", SignerIcons.Stack, R.string.bitcoin_evm_networks_tron_and_solana),
            OnboardingPoint("text.magnifyingglass", SignerIcons.ManageSearch, R.string.requests_are_decoded_before_you_sign),
        ),
    ),
    OnboardingPage(
        hero = SignerIcons.Flight,
        title = R.string.prepare_your_device,
        body = R.string.onboarding_prepare_body,
        points = listOf(
            OnboardingPoint("airplane", SignerIcons.Flight, R.string.turn_on_airplane_mode),
            OnboardingPoint("wifi.slash", SignerIcons.WifiOff, R.string.confirm_wi_fi_cellular_data_and_bluetooth_are_off),
            OnboardingPoint("antenna.radiowaves.left.and.right.slash", SignerIcons.AntennaOff, R.string.onboarding_no_internet_permission),
            OnboardingPoint("eye.slash.fill", SignerIcons.VisibilityOff, R.string.keep_your_recovery_phrase_private_anyone_with_it_can),
        ),
    ),
)
