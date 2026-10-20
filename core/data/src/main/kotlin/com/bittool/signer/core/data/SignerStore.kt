package com.bittool.signer.core.data

import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PublicAccount
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.VaultSecret
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.pairing.PairingPayload
import com.bittool.signer.core.model.pairing.WalletPairingPayload
import com.bittool.signer.core.model.review.TransactionReview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The app state and every operation on wallets, the request pipeline and Activity: the port of iOS `AppStore`
 * (models-state.md §5, engine-contract.md §9). One `@Singleton` (`DefaultSignerStore`, W1-data) bound in `:core:data`'s
 * Hilt module; features inject this interface only — never `SignerEngine`, `EngineCalls` or `WalletVault`.
 *
 * Threading and epochs (models-state.md §12.3):
 * - State changes are confined to the main thread. Suspending functions may be called from any dispatcher and switch
 *   internally (vault/KDF on `Dispatchers.Default`, files on `Dispatchers.IO`, the engine on its own thread).
 *   Non-suspending functions must be called on the main thread.
 * - After **every** suspension point an operation re-checks `securityEpoch` (and its request epoch / sensitive epoch where
 *   iOS does) and throws `CancellationException` when it changed: a late result never repopulates state after
 *   background, lock or capture. UI layers ignore `CancellationException` silently (use `Throwable.toUiText()`).
 * - Commits are success boundaries and the **store** enforces them, not the caller: everything after the persistent
 *   write runs in `withContext(NonCancellable)` and without epoch checks — [createWallet] after `vault.create` (reload,
 *   return), [resetPassword] after `vault.resetPassword` (suspend, reload), [deleteWallet] after `vault.delete` (suspend,
 *   sidecar removal, clear request, navigation reset, reload) and [removeWalletWithLostDeviceKey] after
 *   `vault.removeWithLostDeviceKey` (the same steps), [completeSetup] after the sidecar save (reload), and
 *   [sign] once the result is published (receipt append). A caller whose coroutine is cancelled meanwhile still gets a
 *   consistent state (iOS: "A background transition during the commit must not invite a second import").
 *
 * Errors (DECISIONS.md §9.2, gaps.md G6):
 * - `SignerException`s (`AppIssue`, `VaultError`, `Bip39Error`) carry their UI text.
 * - `EngineException`s authored by the **engine** (resolver, protocol and parser messages) propagate unchanged and are
 *   shown verbatim (`Throwable.toUiText()` → `UiText.Verbatim`), exactly like iOS.
 * - `EngineException`s authored by the **Kotlin host** (`:core:engine` — matched by exact code *and* English message:
 *   ENGINE_MISSING "The bundled signing engine is unavailable. Reinstall BitTool Signer.", ENGINE_INVALID "The bundled
 *   signing engine could not be loaded.", ENGINE_UNAVAILABLE "The offline signing engine could not be started.",
 *   ENTROPY_UNAVAILABLE "The engine’s secure entropy source could not be installed.", ENGINE_FAILURE "The offline engine
 *   could not process this request.", UNSUPPORTED_OPERATION "This signing operation is not supported.",
 *   REQUEST_TOO_LARGE "This request exceeds the 1 MB offline signing limit.", SCAN "The scan session ended. Scan
 *   again.", and the default SIGNING_FAILED "This request could not be signed.") are rethrown by the store as
 *   `SignerException(UiText.Resource(R.string.data_…))` with the same English in `strings_data.xml`, so they are
 *   translated (they are not in the iOS catalogue). Every engine call of the store goes through that one mapping.
 * - Store-authored messages that are not iOS catalogue keys ("The local activity record could not be read.", "The
 *   local activity record could not be saved. Any completed signature remains available.", "The encrypted wallet was
 *   deleted, but its public cache could not be removed.") are published in `SignerState.error` as `UiText.Resource`
 *   from `strings_data.xml`; catalogue messages use `UiText.Ios`.
 *
 * Secret arguments are owned by the caller (wipe after the call); results that carry secrets ([generateMnemonic],
 * [reveal]) are owned by the caller too.
 */
interface SignerStore {
    val state: StateFlow<SignerState>

    // -------------------------------------------------------------------------------------------------------------
    // Lifecycle and session

    /**
     * Loads the wallet list, public indexes, statuses, duplicates and Activity (iOS `reload`, §5.3). Only the latest
     * reload publishes. Failure: `walletLoadSucceeded = false` and `error` set (the list is not cleared). A reload that
     * changes the wallet snapshot while an unsigned plan exists invalidates matching ("Wallet information changed…").
     * Activity is (re)read after every successful load: `receipts = []`, then the journal; a read failure leaves it
     * empty, sets `activityUnreadable = true` and `error` "The local activity record could not be read.".
     * The store starts one reload itself when created unless one was started explicitly.
     */
    suspend fun reload()

    /**
     * iOS `suspend()` (§5.2): renews `securityEpoch`, the sensitive and request epochs; clears `busy` and the signing
     * operation; clears the Connect prefetch; releases the warm engine runtime; sets `matchingIssue = Interrupted` when a
     * review is being matched. Keeps the review, the plan and any finished signature. Called by `:app` on background
     * (`onStop` when not changing configurations), App Lock engaging and screen capture starting; internally after a
     * password reset and a deletion. Main thread.
     */
    fun suspendSession()

    /** Recovery screens call this on appear: a phrase or repair result arriving after they closed is discarded. */
    fun beginSensitiveSession()

    /** …and this on disappear. Both renew the sensitive epoch. */
    fun endSensitiveSession()

    /**
     * Allows or stops the Connect prefetch. `:app` passes `resumed && !locked && !captured && stage == Main`. When
     * allowed and the last load succeeded: after 600 ms, for each ready wallet without an entry, `createWalletPairing` +
     * QR matrices at low priority; a result is dropped (and the loop stops) when cancelled, the epoch changed, that
     * wallet's index changed, or the matrix count differs; an engine error skips that wallet.
     */
    fun setConnectPrefetchAllowed(allowed: Boolean)

    /** The prepared wallet-level QR for [walletId], when the prefetch has finished it. */
    fun preparedConnect(walletId: WalletId): PreparedConnect?

    /** Sets or clears the global "Unable to Continue" alert (iOS `store.error` is publicly settable). */
    fun setError(text: UiText?)

    // -------------------------------------------------------------------------------------------------------------
    // Wallets

    /**
     * A new English recovery phrase of [wordCount] (UI offers 24 and 12) from fresh `SecureRandom` entropy through the
     * engine's `mnemonic`. Discarded (`CancellationException`) when the security or sensitive epoch changed. Errors:
     * "Unable to generate recovery phrase.", `VaultError.InvalidSecret` for an unsupported count.
     */
    suspend fun generateMnemonic(wordCount: Int): SecretText

    /**
     * iOS `createWallet` (§5.3): re-entrancy guard ("A wallet is already being saved."), passphrase ≤ 1,024 UTF-8 bytes,
     * canonical words (`Bip39.validatedCanonical`, exact spellings), native seed → `derivePublicIndex` → index
     * validation → reload (must succeed: "The existing wallet collection could not be read. Retry before adding a
     * wallet.") → duplicate `walletKeyId` refused ("This recovery phrase and passphrase already belong to an existing
     * wallet.") → public sidecar written first (trimmed [name]) → vault create (sidecar removed on failure) → reload.
     * Pass [passphrase] empty when the passphrase option is off. Returns the stored metadata.
     */
    suspend fun createWallet(name: String, mnemonic: SecretText, passphrase: SecretText, password: SecretText): VaultMetadata

    /**
     * The ready wallet whose `walletKeyId` this phrase + passphrase derive, or null (public derivation only: no vault
     * read, no password, no secret returned). Used by the password step to switch into "Reset Password" mode.
     * Canonicalises with `Bip39.validatedCanonical` (exact spellings) first. With duplicates the **first in vault order**
     * is returned (iOS iterates an unordered dictionary; Android is deterministic). Security epoch checked after the
     * engine call.
     */
    suspend fun existingWallet(mnemonic: SecretText, passphrase: SecretText): WalletId?

    /**
     * iOS `resetPassword` (§5.3): the wallet must have an index ("This wallet needs repair before its password can be
     * reset."); the phrase must derive the same `walletKeyId` ("This recovery phrase belongs to a different wallet.");
     * then the vault re-encrypts, the session is suspended and the list reloaded.
     */
    suspend fun resetPassword(walletId: WalletId, mnemonic: SecretText, passphrase: SecretText, newPassword: SecretText)

    /**
     * "Repair": unlocks the vault once, re-derives the public index (encrypted wallet = identity authority), saves the
     * sidecar with the currently displayed name, reloads. Guarded by the security and sensitive epochs.
     */
    suspend fun completeSetup(walletId: WalletId, password: SecretText)

    /**
     * Changes only the sidecar display name (trimmed, 1–40 graphemes, `VaultError.InvalidName`); requires the
     * public-data key ("Repair this wallet before renaming it."); never changes identity or invalidates a plan.
     */
    suspend fun renameWallet(walletId: WalletId, name: String)

    /** Re-encrypts under [new] after verifying [current]; nothing else changes (no suspend, no reload). */
    suspend fun changePassword(walletId: WalletId, current: SecretText, new: SecretText)

    /**
     * Verifies [password] and deletes the vault (a wrong password changes nothing and reloads); then suspends, removes
     * the sidecar (failure → `error` "The encrypted wallet was deleted, but its public cache could not be removed."),
     * clears the request, renews `navigationResetEpoch` and reloads. Activity records are kept.
     */
    suspend fun deleteWallet(walletId: WalletId, password: SecretText)

    /**
     * Whether [walletId]'s device key is provably gone (`WalletVault.isDeviceKeyLost`: no Keystore entry while Keystore
     * demonstrably answers, or a permanently invalidated one), so the wallet can never be unlocked again and
     * [removeWalletWithLostDeviceKey] may remove it without its password (QA backlog B9). False when the wallet is not
     * listed, without a secure lock screen, and whenever the loss is not proven; never throws except
     * `CancellationException`. No password, no counted attempt, no state change.
     *
     * Android-only (iOS has no way out of this dead end). The default is false.
     */
    suspend fun isDeviceKeyLost(walletId: WalletId): Boolean = false

    /**
     * The way out of the lost-device-key dead end (QA backlog B9): such a wallet can be neither unlocked, reset, deleted
     * with its password nor re-imported (its sidecar keeps the `walletKeyId`). Removes it **without its password**: the
     * vault re-proves the loss under its lock (`VaultError.DeviceKeyLossUnconfirmed` when it cannot, and nothing changes)
     * and deletes the vault file, the attempts file and any key remnant; then, exactly as after [deleteWallet]: suspends,
     * removes the sidecar (failure → `error` "The encrypted wallet was deleted, but its public cache could not be
     * removed."), clears the request, renews `navigationResetEpoch` and reloads — so the recovery phrase can be imported
     * again. Committed like [deleteWallet] (a failure reloads and is rethrown). The caller asks for a strong confirmation
     * first; every wallet whose key is not provably gone keeps [deleteWallet]'s password.
     *
     * The default refuses with `VaultError.DeviceKeyLossUnconfirmed`.
     */
    suspend fun removeWalletWithLostDeviceKey(walletId: WalletId) {
        throw VaultError.DeviceKeyLossUnconfirmed()
    }

    /** Decrypts the wallet's secret for the Recovery Phrase page (guarded by both epochs). The caller closes it. */
    suspend fun reveal(walletId: WalletId, password: SecretText): VaultSecret

    /**
     * Receive addresses from the wallet's index (warm runtime). [profile]: the Bitcoin single-sig preset, `""` for
     * Solana (all three presets, no paging), `"standard"` for EVM/TRON. Page size 20, limit 2³¹ (Long). Errors:
     * "Wallet setup required." (no index), "Unable to derive public addresses.".
     */
    suspend fun addresses(walletId: WalletId, chain: Chain, profile: String, start: Long, count: Int): List<PublicAccount>

    /** Coin Level export (`createPublicPairing`); parameters from `ConnectOptions`. "Wallet setup required." without index. */
    suspend fun pairing(walletId: WalletId, chain: Chain, profile: String, outputMode: String, index: Long): PairingPayload

    /** Wallet Level export (`createWalletPairing`, mode `full`). "Wallet setup required." without index. */
    suspend fun walletPairing(walletId: WalletId): WalletPairingPayload

    // -------------------------------------------------------------------------------------------------------------
    // Request pipeline (scan → review → match → sign), models-state.md §5.4

    /**
     * Starts one QR request: a fresh engine scan-session runtime (`scanBegin`). Call when the scanner appears / "Scan
     * Again". May throw (engine start failure); the scanner shows the message and offers "Scan Again".
     */
    suspend fun beginScan()

    /** Ends the scan session (scanner dismissed, also from `onCleared`). */
    suspend fun endScan()

    /**
     * Adds one scanned frame (exactly as scanned): `clearRequest()`, `scanAdd`, epoch check; on `complete` with a
     * request object runs [prepare]. Returns the progress (`progress` as Double, else 0 without / 1 with a review).
     */
    suspend fun receive(frame: String): Double

    /** Batch fallback (`decodeQR`; tests): ≤ 2,048 frames and ≤ 1,048,576 UTF-8 bytes ("QR transfer is too large."). */
    suspend fun receive(frames: List<String>): Double

    /**
     * Paste / file import: `clearRequest()`, ≤ 262,144 UTF-8 bytes ("Text payload exceeds 256 KB."), `decodePayload`,
     * requires `complete` with a request ("The request is incomplete. Import all QR frames."), then [prepare].
     */
    suspend fun importPayload(text: String)

    /**
     * Freezes [request] (sorted keys, ≤ 1 MiB: "Invalid request format." / "Request exceeds the supported size."),
     * reviews it (commitment checks: "Missing or invalid transaction commitment."), publishes the review, then matches
     * every wallet (`resolveSigningPlan`) and publishes the plan (refined review when consistent) or a `matchingIssue`.
     * Errors before the review is published are thrown; matching errors become `matchingIssue`.
     */
    suspend fun prepare(request: JsonObject)

    /** Re-runs [prepare] on the frozen request when reviewed without plan or signature ("No interrupted or unmatched request to retry."). */
    suspend fun retryMatching()

    /**
     * Signs the first pending task (strictly in plan order) with that wallet's [password]: preconditions (§5.4 steps
     * 1–3), vault unlock **before** any engine call, `signPlanTask` (+ `previousResult`), result identity checks
     * ("Request changed. Review the transaction again."), `encodeResponse` for the final task, a receipt for every task.
     * [riskAcknowledged] is this request's consent, required when `review.requiresAcknowledgement`.
     *
     * `busy` is true from the precondition check until this call ends (only for the current operation). A
     * [suspendSession] meanwhile resets `busy`, and the late result is discarded (`CancellationException`): the review
     * and plan stay, so the person can sign again with the password. After the response is published (final task) or
     * the task is marked completed (earlier tasks) the receipt append is non-cancellable; this function returns after
     * the journal write was attempted, so `receipts` is current when it returns (a write failure sets `error` only).
     */
    suspend fun sign(taskId: String, password: SecretText, riskAcknowledged: Boolean = false)

    /** Drops the request, plan, signature and pending data (Reject, Done, a new scan). Main thread. */
    fun clearRequest()

    // -------------------------------------------------------------------------------------------------------------
    // Activity, models-state.md §5.5 / §6

    /**
     * Saves an empty journal and publishes it ("Clear Activity"); failure → `error` (could not be saved). Overwrites an
     * unreadable journal too (the explicit recovery path; also clears `activityUnreadable`).
     */
    suspend fun clearHistory()

    /**
     * Re-reviews a stored request offline (fresh runtime, no password, no matching, never touches the pending
     * request): ≤ 32,768 UTF-8 bytes and parseable ("The stored request could not be read."), same `requestHash`
     * case-insensitively and non-empty fields ("The stored request does not match this signature's fingerprint.").
     */
    suspend fun reviewStoredRequest(json: String, expectedRequestHash: String): TransactionReview
}
