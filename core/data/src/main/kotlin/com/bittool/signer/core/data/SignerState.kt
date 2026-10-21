package com.bittool.signer.core.data

import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.QrMatrix
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.pairing.QrPayloadView
import com.bittool.signer.core.model.pairing.WalletPairingPayload
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.model.signing.MatchingIssue
import com.bittool.signer.core.model.signing.SigningPlan
import com.bittool.signer.core.model.signing.SigningReceipt
import com.bittool.signer.core.model.signing.SigningTask

/**
 * Every published property of iOS `AppStore` (models-state.md §5.1) as one immutable snapshot. Holds no secret: there
 * is no active wallet, no unlocked wallet, no reusable signing authorisation and no retained clear secret.
 *
 * Collect with `collectAsStateWithLifecycle()` and map to a screen `UiState` in the ViewModel (derive, do not copy).
 */
data class SignerState(
    /** Vault order (createdAt ascending); `name` already replaced by the sidecar display name when present. */
    val wallets: List<VaultMetadata> = emptyList(),
    /** True until the first [SignerStore.reload] finishes (root stage `Loading`). */
    val isLoading: Boolean = true,
    /** Whether the last reload succeeded. A failed load is never treated as an empty list (stage `Failed`). */
    val walletLoadSucceeded: Boolean = false,
    /** Only wallets with a valid authenticated index. */
    val publicWallets: Map<WalletId, WalletPublicMetadata> = emptyMap(),
    val metadataStatuses: Map<WalletId, PublicMetadataStatus> = emptyMap(),
    /** Wallets sharing a `walletKeyId` with another: warned about, never signable. */
    val duplicateWalletIds: Set<WalletId> = emptySet(),
    /** The request under review. A new non-null `review.requestHash` selects the Scan tab. */
    val review: TransactionReview? = null,
    /** The fixed signing plan for [review]. */
    val plan: SigningPlan? = null,
    /** Why there is no plan (iOS `matchingError`). */
    val matchingIssue: MatchingIssue? = null,
    val completedTaskIds: Set<String> = emptySet(),
    /** The final response QR frames. */
    val signatureFrames: List<String> = emptyList(),
    /** The final response text (file export): frames joined by "\n". */
    val signatureText: String = "",
    val signaturePayload: QrPayloadView? = null,
    /** Activity, newest first. */
    val receipts: List<SigningReceipt> = emptyList(),
    /**
     * The last journal read failed (corrupt file, lost Keystore key): [receipts] is empty and new receipts cannot be
     * appended until the journal is cleared (iOS never overwrites an unreadable journal silently). iOS then hides
     * "Clear" because the list is empty (activity-settings.md §2.2 "iOS gap"); Android keeps that by default — the flag
     * exists so the Activity screen *may* offer "Clear Activity" as the explicit recovery path if product decides so.
     */
    val activityUnreadable: Boolean = false,
    /**
     * Android-only (QA L1): the journal's Keystore key was provably lost (removing the device's screen lock deletes
     * it), so the store started a new, empty journal instead of reporting "could not be read" on every load. The Activity
     * tab tells the person once, until [SignerStore.acknowledgeActivityRestart]; persisted across launches.
     */
    val activityRestarted: Boolean = false,
    /**
     * Android-only (QA L4): the wallets whose device key is provably gone (`WalletVault.isDeviceKeyLost`). Every reload
     * and [SignerStore.refreshDeviceKeys] look at the wallets without a ready public index; a wallet with a ready index
     * is listed once another proof found its loss ([SignerStore.isDeviceKeyLost]: setup's duplicate check, the Delete
     * page). Such a wallet can never be unlocked or repaired; the only way on is "Remove This Wallet" and a new import.
     * A proven loss is final, so it is not proven again for as long as the wallet is listed. Empty without a secure lock
     * screen ([deviceSecure]). Display only: the removal proves the loss again in the vault.
     */
    val lostKeyWalletIds: Set<WalletId> = emptySet(),
    /**
     * Android-only (QA L4): the device had a secure lock screen at the last look (reload, [SignerStore.refreshDeviceKeys]).
     * Without one no wallet can be unlocked or repaired ("Set a screen lock…").
     */
    val deviceSecure: Boolean = true,
    /** A signature is in flight (Sign buttons show a spinner, the authorize sheet cannot be dismissed). */
    val busy: Boolean = false,
    /**
     * Renewed by every [SignerStore.suspendSession] (background, App Lock, screen capture, password reset, deletion).
     * Every holder of a typed secret, a revealed phrase, a risk acknowledgement or a pending password prompt observes it
     * and clears itself (app-shell.md §4.6 consumer table); late results under an old epoch are discarded.
     */
    val securityEpoch: Long = 0,
    /** Renewed after a wallet is deleted: the Wallets stack resets and an unfinished setup clears its secrets. */
    val navigationResetEpoch: Long = 0,
    /** The global "Unable to Continue" alert text (with "OK", which calls [SignerStore.setError] with null). */
    val error: UiText? = null,
) {
    /** A finished signature exists (the Scan tab shows the result page). */
    val hasSignedResponse: Boolean get() = signatureFrames.isNotEmpty() || signatureText.isNotEmpty()

    /** The plan's tasks not yet signed, in plan order (signing is strictly in this order). */
    val pendingTasks: List<SigningTask> get() = plan?.tasks?.filter { it.id !in completedTaskIds } ?: emptyList()

    /** Some wallet has no valid index. */
    val hasIncompleteWallets: Boolean get() = wallets.any { publicWallets[it.id] == null }

    /** The Scan tab badge "1": a review is pending and unsigned. */
    val showsScanBadge: Boolean get() = review != null && !hasSignedResponse

    fun wallet(id: WalletId): VaultMetadata? = wallets.firstOrNull { it.id == id }

    /** The displayed name, or null for an unknown/deleted wallet (UI shows "Wallet" / "Deleted wallet"). */
    fun displayName(id: WalletId): String? = wallet(id)?.name

    fun publicMetadata(id: WalletId): WalletPublicMetadata? = publicWallets[id]

    /** iOS `metadataStatus(for:)`: defaults to needs-completion. */
    fun metadataStatus(id: WalletId): PublicMetadataStatus = metadataStatuses[id] ?: PublicMetadataStatus.NeedsCompletion

    /** Avatar colour seed: the `walletKeyId`, else the UUID in uppercase (iOS parity). Never used for signing decisions. */
    fun avatarIdentity(id: WalletId): String = publicWallets[id]?.walletKeyId ?: id.uppercase

    /** Uppercase master fingerprint (`73C5DA0A`), or null (UI shows "Setup required"). Display only. */
    fun fingerprint(id: WalletId): String? = publicWallets[id]?.displayFingerprint

    fun isDuplicate(id: WalletId): Boolean = id in duplicateWalletIds

    /** QA L4: [id]'s device key is provably gone (see [lostKeyWalletIds]); only "Remove This Wallet" helps. */
    fun hasLostDeviceKey(id: WalletId): Boolean = id in lostKeyWalletIds
}

/**
 * Connect's opening wallet-level QR, prepared after the wallets load so the first push into Connect has no engine or
 * QR work left (models-state.md §5.3). Public data only, in memory; cleared by every suspend and reload.
 */
data class PreparedConnect(val payload: WalletPairingPayload, val matrices: List<QrMatrix>)
