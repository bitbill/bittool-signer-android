package com.bittool.signer.core.data

import com.bittool.signer.core.common.coroutines.ApplicationScope
import com.bittool.signer.core.common.coroutines.DefaultDispatcher
import com.bittool.signer.core.common.coroutines.MainDispatcher
import com.bittool.signer.core.common.coroutines.runCatchingCancellable
import com.bittool.signer.core.common.time.WallClock
import com.bittool.signer.core.crypto.Bip39Seed
import com.bittool.signer.core.crypto.SecureRandomSource
import com.bittool.signer.core.crypto.bip39.Bip39
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.crypto.vault.WalletVault
import com.bittool.signer.core.engine.EngineCalls
import com.bittool.signer.core.engine.EngineException
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PublicAccount
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.QrCodeEncoder
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.VaultSecret
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.json.SignerJson
import com.bittool.signer.core.model.json.bool
import com.bittool.signer.core.model.json.canonicalJson
import com.bittool.signer.core.model.json.double
import com.bittool.signer.core.model.json.obj
import com.bittool.signer.core.model.json.objects
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.json.strings
import com.bittool.signer.core.model.pairing.PairingPayload
import com.bittool.signer.core.model.pairing.QrPayloadView
import com.bittool.signer.core.model.pairing.WalletPairingPayload
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.model.signing.MatchingIssue
import com.bittool.signer.core.model.signing.PlanCandidate
import com.bittool.signer.core.model.signing.ReceiptJournal
import com.bittool.signer.core.model.signing.SigningPlan
import com.bittool.signer.core.model.signing.SigningReceipt
import com.bittool.signer.core.model.signing.SigningTask
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.core.model.useAndWipe
import com.bittool.signer.core.model.util.Hex
import com.bittool.signer.core.model.util.trimWhitespacesAndNewlines
import com.bittool.signer.core.model.util.utf8Size
import com.bittool.signer.core.model.util.wipe
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The port of iOS `Models/AppStore.swift` (models-state.md §5, engine-contract.md §9, scan-sign.md §1, §7–§9, §14,
 * §16, §18): the one source of truth for wallets, the request pipeline and Activity. The normative contract is the
 * KDoc of [SignerStore]; this file cites the iOS lines it reproduces.
 *
 * Design rule (`AppStore.swift:14-15`): there is no active wallet, no unlocked wallet, no reusable signing
 * authorisation and no retained clear secret. Every secret operation takes a password, uses the secret once and
 * wipes it.
 *
 * Concurrency (iOS `@MainActor`):
 * - every piece of mutable state below is confined to [main]; each public suspending function switches to it, and the
 *   non-suspending ones must be called on the main thread;
 * - vault and BIP39 work run on [default], the engine on its own thread (inside [EngineCalls]), files on the
 *   repositories' I/O dispatcher;
 * - after every suspension an operation re-checks the epochs it captured ([requireSession]) and throws
 *   [CancellationException] when they changed, so a late result never repopulates state after a suspend, a lock, a
 *   capture or a replaced request;
 * - the commit points run under [NonCancellable] (SignerStore KDoc "Commits are success boundaries").
 *
 * Android differences (documented, deliberate):
 * - Activity is read after each successful load and published in one step (receipts and `activityUnreadable`
 *   together) instead of "clear, then read": the journal read suspends on Android, and clearing first would make the
 *   Activity list flicker on every reload. The result is the same as iOS.
 * - `walletLoadSucceeded` is set before the Connect prefetch is scheduled on a load (iOS schedules first, so its very
 *   first load never schedules a prefetch by itself; `:app` allows the prefetch right after the first load anyway).
 * - Candidates for `resolveSigningPlan` carry the lowercase wallet UUID (engine-contract.md §9 item 4).
 * - Vault unlocks run to completion even when the caller is cancelled, and the secret is captured before control
 *   returns, so a decrypted secret is always wiped here instead of being dropped unwiped with a discarded result.
 * - Every commit region is a single `withContext(NonCancellable)` on [main] with its dispatcher switches nested inside:
 *   returning from a switch straight into a cancelled caller would throw before the commit finished.
 */
@Singleton
class DefaultSignerStore @Inject constructor(
    private val vault: WalletVault,
    private val engine: EngineCalls,
    private val publicRepository: PublicWalletRepository,
    private val receiptRepository: ReceiptRepository,
    private val bip39: Bip39,
    private val bip39Seed: Bip39Seed,
    private val random: SecureRandomSource,
    private val qrEncoder: QrCodeEncoder,
    private val clock: WallClock,
    @MainDispatcher private val main: CoroutineDispatcher,
    @DefaultDispatcher private val default: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) : SignerStore {

    private val _state = MutableStateFlow(SignerState())
    override val state: StateFlow<SignerState> = _state.asStateFlow()
    private val current: SignerState get() = _state.value

    // ---- Main-confined private state (AppStore.swift:37-62) -----------------------------------------------------

    private var sensitiveEpoch = 0L
    private var requestEpoch = 0L
    private var reloadEpoch = 0L
    private var hasStartedReload = false
    private var operationCounter = 0L
    private var signingOperation: Long? = null
    /** The frozen request (iOS `pendingRequestData`): the one object every engine call of this request receives. */
    private var pendingRequest: Frozen? = null
    /** The frozen last `signPlanTask` result (iOS `previousResultData`). */
    private var previousResult: JsonObject? = null
    private var creatingWallet = false
    private var planWalletSnapshot: WalletCollectionSnapshot? = null
    private var connectPrefetch: Map<WalletId, PreparedConnect> = emptyMap()
    private var prefetchJob: Job? = null
    private var prefetchAllowed = false
    private var scanFrames: List<String> = emptyList()
    private var incrementalScan = false
    /** Serialises every read-modify-write of the Activity journal (iOS does them synchronously on the main actor). */
    private val journal = Mutex()

    /** iOS `WalletCollectionSnapshot` (`:49-52`): the wallet IDs and each index's exact canonical JSON. */
    private data class WalletCollectionSnapshot(val walletIds: Set<WalletId>, val indexes: Map<WalletId, String>)

    /** iOS `frozen` (`:117-122`) output: the immutable object and its sorted-keys text. */
    private class Frozen(val json: JsonObject, val text: String)

    init {
        // AppStore.swift:75-79: an explicit reload right after creation wins over this later-scheduled bootstrap load.
        scope.launch(main) {
            yield()
            if (!hasStartedReload) reload()
        }
    }

    // =============================================================================================================
    // Lifecycle and session (AppStore.swift:82-148)

    override suspend fun reload() = withContext(main) {
        hasStartedReload = true
        val loading = ++reloadEpoch
        try {
            val originals = withContext(default) { vault.list() }
            val key = if (originals.isEmpty()) {
                null
            } else {
                runCatchingCancellable { withContext(default) { vault.publicDataKey(createIfMissing = false) } }.getOrNull()
            }
            val displayed = ArrayList<VaultMetadata>(originals.size)
            val indexes = LinkedHashMap<WalletId, WalletPublicMetadata>()
            val statuses = LinkedHashMap<WalletId, PublicMetadataStatus>()
            for (wallet in originals) {
                val snapshot = publicRepository.snapshot(wallet.id, key)
                displayed += wallet.copy(name = snapshot.displayName ?: wallet.name)
                snapshot.metadata?.let { indexes[wallet.id] = it }
                statuses[wallet.id] = snapshot.status
            }
            if (loading != reloadEpoch) return@withContext
            // AppStore.swift:140-141: every wallet whose ready index shares its walletKeyId with another.
            val duplicates = indexes.entries.groupBy({ it.value.walletKeyId }, { it.key }).values
                .filter { it.size > 1 }.flatten().toSet()
            clearConnectPrefetch()
            _state.update {
                it.copy(
                    wallets = displayed,
                    publicWallets = indexes,
                    metadataStatuses = statuses,
                    duplicateWalletIds = duplicates,
                    walletLoadSucceeded = true,
                )
            }
            scheduleConnectPrefetch()
            val loaded = current
            if (loaded.plan != null && !loaded.hasSignedResponse && planWalletSnapshot != walletCollectionSnapshot()) {
                invalidateMatching()
            }
            loadReceipts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (loading == reloadEpoch) _state.update { it.copy(walletLoadSucceeded = false, error = e.toUiText()) }
        }
        if (loading == reloadEpoch) _state.update { it.copy(isLoading = false) }
    }

    override fun suspendSession() {
        // AppStore.swift:86-94.
        sensitiveEpoch++
        requestEpoch++
        signingOperation = null
        clearConnectPrefetch()
        scope.launch { runCatchingCancellable { engine.releaseWarmContext() } }
        _state.update { s ->
            val interrupted = s.review != null && s.plan == null && s.matchingIssue == null
            s.copy(
                securityEpoch = s.securityEpoch + 1,
                busy = false,
                matchingIssue = if (interrupted) MatchingIssue.Interrupted else s.matchingIssue,
            )
        }
    }

    override fun beginSensitiveSession() {
        sensitiveEpoch++
    }

    override fun endSensitiveSession() {
        sensitiveEpoch++
    }

    override fun setConnectPrefetchAllowed(allowed: Boolean) {
        // AppStore.swift:270-273.
        prefetchAllowed = allowed
        if (allowed) {
            scheduleConnectPrefetch()
        } else {
            prefetchJob?.cancel()
            prefetchJob = null
        }
    }

    override fun preparedConnect(walletId: WalletId): PreparedConnect? = connectPrefetch[walletId]

    override fun setError(text: UiText?) {
        _state.update { it.copy(error = text) }
    }

    override fun clearRequest() {
        // AppStore.swift:95-100.
        requestEpoch++
        signingOperation = null
        planWalletSnapshot = null
        pendingRequest = null
        previousResult = null
        _state.update {
            it.copy(
                busy = false,
                review = null,
                plan = null,
                matchingIssue = null,
                completedTaskIds = emptySet(),
                signatureFrames = emptyList(),
                signatureText = "",
                signaturePayload = null,
            )
        }
    }

    /**
     * AppStore.swift:101-108: a fixed plan cannot silently acquire or drop wallets. Every partial signature is cleared
     * and the request must be matched again, with fresh passwords. The review and the frozen request stay.
     */
    private fun invalidateMatching() {
        suspendSession()
        planWalletSnapshot = null
        previousResult = null
        _state.update {
            it.copy(
                plan = null,
                completedTaskIds = emptySet(),
                signatureFrames = emptyList(),
                signatureText = "",
                signaturePayload = null,
                matchingIssue = MatchingIssue.WalletsChanged,
            )
        }
    }

    /** iOS `requireSession` (`:109-112`). */
    private suspend fun requireSession(epoch: Long, requestId: Long? = null) {
        currentCoroutineContext().ensureActive()
        if (epoch != current.securityEpoch || (requestId != null && requestId != requestEpoch)) {
            throw CancellationException("The session changed; the late result is discarded.")
        }
    }

    private fun requireSensitive(sensitive: Long) {
        if (sensitive != sensitiveEpoch) throw CancellationException("The sensitive session ended; the late result is discarded.")
    }

    private fun walletCollectionSnapshot(): WalletCollectionSnapshot = current.let { s ->
        WalletCollectionSnapshot(s.wallets.map { it.id }.toSet(), s.publicWallets.mapValues { it.value.canonicalJson })
    }

    // =============================================================================================================
    // Connect prefetch (AppStore.swift:255-301)

    private fun clearConnectPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
        connectPrefetch = emptyMap()
    }

    private fun scheduleConnectPrefetch() {
        if (!prefetchAllowed || !current.walletLoadSucceeded) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch(main) {
            // Let launch, unlock or the foreground transition finish first.
            delay(PREFETCH_DELAY_MILLIS)
            prefetchConnectPairings()
        }
    }

    private suspend fun prefetchConnectPairings() {
        for (wallet in current.wallets) {
            if (connectPrefetch[wallet.id] != null || current.metadataStatus(wallet.id) != PublicMetadataStatus.Ready) continue
            if (!currentCoroutineContext().isActive) return
            val index = current.publicMetadata(wallet.id)?.canonicalJson ?: return
            val epoch = current.securityEpoch
            val payload = try {
                walletPairing(wallet.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            val matrices = withContext(default) {
                payload.frames.mapNotNull { frame -> runCatchingCancellable { qrEncoder.encode(frame) }.getOrNull() }
            }
            // Discard a result that outlived a lock, background or wallet change.
            if (!currentCoroutineContext().isActive || epoch != current.securityEpoch ||
                current.publicMetadata(wallet.id)?.canonicalJson != index || matrices.size != payload.frames.size
            ) {
                return
            }
            connectPrefetch = connectPrefetch + (wallet.id to PreparedConnect(payload, matrices))
        }
    }

    // =============================================================================================================
    // Wallets (AppStore.swift:160-252, 303-309, 474-531)

    override suspend fun generateMnemonic(wordCount: Int): SecretText {
        // AppStore.swift:160-168. The result leaves this function directly (no dispatch after the checks).
        val (epoch, sensitive) = withContext(main) { current.securityEpoch to sensitiveEpoch }
        currentCoroutineContext().ensureActive()
        val entropy = random.entropyHex(wordCount)
        val result = try {
            engineCall { engine.mnemonic(entropy) }
        } finally {
            entropy.wipe()
        }
        withContext(main) {
            requireSession(epoch)
            requireSensitive(sensitive)
        }
        val words = result.str("mnemonic") ?: result.str("value") ?: throw AppIssue("Unable to generate recovery phrase.")
        return SecretText.of(words)
    }

    override suspend fun createWallet(name: String, mnemonic: SecretText, passphrase: SecretText, password: SecretText): VaultMetadata =
        withContext(main) {
            // AppStore.swift:177-211.
            if (creatingWallet) throw AppIssue("A wallet is already being saved.")
            creatingWallet = true
            try {
                val epoch = current.securityEpoch
                if (passphrase.utf8Size() > MAX_PASSPHRASE_BYTES) throw AppIssue("The BIP39 passphrase exceeds 1,024 UTF-8 bytes.")
                // The vault always stores the canonical words the seed is derived from.
                canonical(mnemonic).useAndWipe { words ->
                    val raw = derivePublicIndex(words, passphrase)
                    requireSession(epoch)
                    val index = WalletPublicMetadata.parse(raw)
                    reload()
                    requireSession(epoch)
                    if (!current.walletLoadSucceeded) {
                        throw AppIssue("The existing wallet collection could not be read. Retry before adding a wallet.")
                    }
                    if (current.publicWallets.values.any { it.walletKeyId == index.walletKeyId }) {
                        throw AppIssue("This recovery phrase and passphrase already belong to an existing wallet.")
                    }
                    // Prepare the public sidecar first. It is invisible without a committed vault, so a crash cannot leave
                    // a visible new wallet with missing setup.
                    val id = WalletId.random()
                    val key = withContext(default) { vault.publicDataKey(createIfMissing = true) } ?: throw VaultError.DeviceKeyUnavailable()
                    try {
                        publicRepository.save(index, id, name.trimWhitespacesAndNewlines(), key)
                        requireSession(epoch)
                    } catch (e: Throwable) {
                        withContext(NonCancellable) { runCatching { publicRepository.remove(id) } }
                        throw e
                    }
                    // Persistence is the success boundary: from the vault write on nothing is cancellable, so a background
                    // transition (or a caller that goes away) during the commit cannot invite a second import of the
                    // same wallet. One NonCancellable block on main: a dispatcher switch nested inside it never resumes
                    // into a cancelled coroutine.
                    withContext(NonCancellable) {
                        val metadata = try {
                            withContext(default) { vault.create(name, words, passphrase, password, id) }
                        } catch (e: Throwable) {
                            runCatching { publicRepository.remove(id) }
                            throw e
                        }
                        reload()
                        metadata
                    }
                }
            } finally {
                creatingWallet = false
            }
        }

    override suspend fun existingWallet(mnemonic: SecretText, passphrase: SecretText): WalletId? = withContext(main) {
        // AppStore.swift:484-491. Public derivation only: no vault read, no password, no secret returned.
        val epoch = current.securityEpoch
        val raw = canonical(mnemonic).useAndWipe { derivePublicIndex(it, passphrase) }
        requireSession(epoch)
        val key = WalletPublicMetadata.parse(raw).walletKeyId
        val loaded = current
        loaded.wallets.firstOrNull { loaded.publicWallets[it.id]?.walletKeyId == key }?.id
    }

    override suspend fun resetPassword(walletId: WalletId, mnemonic: SecretText, passphrase: SecretText, newPassword: SecretText) =
        withContext(main) {
            // AppStore.swift:498-512: the phrase must derive this very wallet before anything is written, or one wallet's
            // phrase could overwrite another's stored secret.
            val existing = current.publicMetadata(walletId)
                ?: throw AppIssue("This wallet needs repair before its password can be reset.")
            val epoch = current.securityEpoch
            canonical(mnemonic).useAndWipe { words ->
                val raw = derivePublicIndex(words, passphrase)
                requireSession(epoch)
                val derived = WalletPublicMetadata.parse(raw)
                if (derived.walletKeyId != existing.walletKeyId) throw AppIssue("This recovery phrase belongs to a different wallet.")
                withContext(NonCancellable) {
                    withContext(default) { vault.resetPassword(walletId, words, passphrase, newPassword) }
                    // The password that was authorising this session is gone.
                    suspendSession()
                    reload()
                }
            }
        }

    override suspend fun completeSetup(walletId: WalletId, password: SecretText) = withContext(main) {
        // AppStore.swift:213-233. The encrypted wallet, never the old cache, is the identity authority.
        if (current.wallet(walletId) == null) throw VaultError.WalletNotFound()
        val epoch = current.securityEpoch
        val sensitive = sensitiveEpoch
        val raw = withSecret(walletId, password) { secret ->
            requireSession(epoch)
            derivePublicIndex(secret.mnemonic, secret.passphrase)
        }
        requireSession(epoch)
        requireSensitive(sensitive)
        val index = WalletPublicMetadata.parse(raw)
        withContext(NonCancellable) {
            val key = withContext(default) { vault.publicDataKey(createIfMissing = true) } ?: throw VaultError.DeviceKeyUnavailable()
            publicRepository.save(index, walletId, current.displayName(walletId) ?: UNKNOWN_WALLET_LABEL, key)
            // Existing duplicate encrypted records are kept and reported; the resolver rejects an ambiguous identity
            // instead of deleting user data.
            reload()
        }
    }

    override suspend fun renameWallet(walletId: WalletId, name: String) = withContext(main) {
        // AppStore.swift:235-242. Only the sidecar display name changes; identity and plans stay valid.
        if (current.wallet(walletId) == null) throw VaultError.WalletNotFound()
        val key = withContext(default) { vault.publicDataKey(createIfMissing = false) }
            ?: throw AppIssue("Repair this wallet before renaming it.")
        withContext(NonCancellable) {
            publicRepository.rename(walletId, name, key)
            reload()
        }
    }

    override suspend fun changePassword(walletId: WalletId, current: SecretText, new: SecretText) {
        // AppStore.swift:516-519: keys, the wallet ID, public data, reviews and navigation all stay valid.
        currentCoroutineContext().ensureActive()
        withContext(default) { vault.changePassword(walletId, current, new) }
    }

    override suspend fun deleteWallet(walletId: WalletId, password: SecretText) = withContext(main) {
        // AppStore.swift:520-531. The vault verifies the password before removing anything, so a wrong password leaves
        // every session intact and its error reaches the sheet.
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            try {
                withContext(default) { vault.delete(walletId, password) }
            } catch (e: Throwable) {
                reload()
                throw e
            }
            suspendSession()
            try {
                publicRepository.remove(walletId)
            } catch (e: Exception) {
                setError(UiText.Resource(R.string.data_public_cache_not_removed))
            }
            clearRequest()
            _state.update { it.copy(navigationResetEpoch = it.navigationResetEpoch + 1) }
            reload()
        }
    }

    override suspend fun reveal(walletId: WalletId, password: SecretText): VaultSecret {
        // AppStore.swift:474-480. The secret leaves this function directly (no dispatch after the checks); a secret that
        // arrives after its screen closed or the session ended is wiped here.
        val (epoch, sensitive) = withContext(main) { current.securityEpoch to sensitiveEpoch }
        val holder = SecretHolder()
        try {
            unlockInto(holder, walletId, password)
            withContext(main) {
                requireSession(epoch)
                requireSensitive(sensitive)
            }
        } catch (e: Throwable) {
            holder.secret?.close()
            throw e
        }
        return checkNotNull(holder.secret)
    }

    override suspend fun addresses(walletId: WalletId, chain: Chain, profile: String, start: Long, count: Int): List<PublicAccount> =
        withContext(main) {
            // AppStore.swift:243-248.
            val metadata = current.publicMetadata(walletId) ?: throw AppIssue("Wallet setup required.")
            val result = engineCall { engine.derivePublicAddresses(metadata, chain, profile, start, count) }
            val addresses = result.objects("addresses") ?: throw AppIssue("Unable to derive public addresses.")
            addresses.map(PublicAccount::parse)
        }

    override suspend fun pairing(walletId: WalletId, chain: Chain, profile: String, outputMode: String, index: Long): PairingPayload =
        withContext(main) {
            // AppStore.swift:249-253.
            val metadata = current.publicMetadata(walletId) ?: throw AppIssue("Wallet setup required.")
            PairingPayload.parse(engineCall { engine.createPublicPairing(metadata, chain, profile, outputMode, index) })
        }

    override suspend fun walletPairing(walletId: WalletId): WalletPairingPayload = withContext(main) {
        // AppStore.swift:305-309: the account-level (`full`) payload, the only wallet-level form.
        val metadata = current.publicMetadata(walletId) ?: throw AppIssue("Wallet setup required.")
        WalletPairingPayload.parse(engineCall { engine.createWalletPairing(metadata) })
    }

    // =============================================================================================================
    // Request pipeline (AppStore.swift:311-472)

    override suspend fun beginScan() = withContext(main) {
        // AppStore.swift:316-321: a fresh engine scan-session runtime.
        scanFrames = emptyList()
        engineCall { engine.beginScanSession() }
        incrementalScan = true
    }

    override suspend fun endScan() = withContext(NonCancellable + main) {
        // AppStore.swift:323-327. Runs to completion even from a cancelled caller (the scanner is closing).
        scanFrames = emptyList()
        if (incrementalScan) runCatchingCancellable { engine.endScanSession() }
        incrementalScan = false
    }

    override suspend fun receive(frame: String): Double = withContext(main) {
        // AppStore.swift:330-341.
        if (!incrementalScan) {
            if (frame !in scanFrames) scanFrames = scanFrames + frame
            return@withContext receive(scanFrames)
        }
        clearRequest()
        val epoch = current.securityEpoch
        val incoming = requestEpoch
        val decoded = engineCall { engine.scan(frame) }
        requireSession(epoch, incoming)
        completeDecoded(decoded)
    }

    override suspend fun receive(frames: List<String>): Double = withContext(main) {
        // AppStore.swift:343-351.
        clearRequest()
        val epoch = current.securityEpoch
        val incoming = requestEpoch
        if (frames.size > MAX_FRAMES || frames.sumOf { it.utf8Size().toLong() } > MAX_REQUEST_BYTES) {
            throw AppIssue("QR transfer is too large.")
        }
        val decoded = engineCall { engine.decodeQr(frames) }
        requireSession(epoch, incoming)
        completeDecoded(decoded)
    }

    /** The shared tail of both `receive` paths: prepare a complete request, report the progress. */
    private suspend fun completeDecoded(decoded: JsonObject): Double {
        val request = decoded.obj("request")
        if (decoded.bool("complete") == true && request != null) prepare(request)
        return decoded.double("progress") ?: if (current.review == null) 0.0 else 1.0
    }

    override suspend fun importPayload(text: String) = withContext(main) {
        // AppStore.swift:352-360.
        clearRequest()
        val epoch = current.securityEpoch
        val incoming = requestEpoch
        if (text.utf8Size() > MAX_TEXT_PAYLOAD_BYTES) throw AppIssue("Text payload exceeds 256 KB.")
        val decoded = engineCall { engine.decodePayload(text) }
        requireSession(epoch, incoming)
        val request = decoded.obj("request")
        if (decoded.bool("complete") != true || request == null) throw AppIssue("The request is incomplete. Import all QR frames.")
        prepare(request)
    }

    override suspend fun prepare(request: JsonObject) = withContext(main) {
        // AppStore.swift:361-403.
        clearRequest()
        val epoch = current.securityEpoch
        val preparing = requestEpoch
        val frozen = withContext(default) { freeze(request) }
        val result = engineCall { engine.review(frozen.json) }
        requireSession(epoch, preparing)
        val reviewed = TransactionReview.parse(result)
        val chain = result.str("chain")
        if (!Hex.isHash64(reviewed.requestHash) || chain != frozen.json.str("chain") || Chain.fromWire(chain) == null ||
            reviewed.fields.isEmpty()
        ) {
            throw AppIssue("Missing or invalid transaction commitment.")
        }
        pendingRequest = frozen
        _state.update { it.copy(review = reviewed) }
        if (result.bool("canSign") != true) {
            _state.update { it.copy(matchingIssue = MatchingIssue.Unsupported) }
            return@withContext
        }
        val collection = walletCollectionSnapshot()
        val candidates = current.let { s -> s.wallets.map { PlanCandidate(it.id, s.publicWallets[it.id], s.metadataStatus(it.id)) } }
        try {
            val resolved = engineCall { engine.resolveSigningPlan(frozen.json, candidates) }
            requireSession(epoch, preparing)
            if (collection != walletCollectionSnapshot()) {
                _state.update { it.copy(matchingIssue = MatchingIssue.WalletsChanged) }
                return@withContext
            }
            if (resolved.str("status") != "ready") {
                val message = resolved.str("message") ?: MatchingIssue.NO_MATCHING_WALLET
                _state.update { it.copy(matchingIssue = MatchingIssue.Engine(message)) }
                return@withContext
            }
            val fixed = SigningPlan.parse(resolved, reviewed.requestHash)
            if (!fixed.tasks.all { current.publicWallets[it.walletId]?.walletKeyId == it.walletKeyId }) {
                throw AppIssue("Invalid signing wallet identity.")
            }
            planWalletSnapshot = collection
            // The plan's review adds facts proven with the matched wallets' public keys (e.g. verified Bitcoin change)
            // for the same request commitment.
            val refined = resolved.obj("review")?.let(TransactionReview::parse)?.takeIf {
                it.requestHash == reviewed.requestHash && it.chain == reviewed.chain && it.fields.isNotEmpty()
            }
            _state.update { it.copy(plan = fixed, matchingIssue = null, review = refined ?: it.review) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            requireSession(epoch, preparing)
            _state.update { it.copy(matchingIssue = MatchingIssue.Failure(e.toUiText() ?: UiText.Verbatim(e.javaClass.simpleName))) }
        }
    }

    override suspend fun retryMatching() = withContext(main) {
        // AppStore.swift:405-410.
        val frozen = pendingRequest
        val s = current
        if (s.plan != null || s.hasSignedResponse || s.review == null || frozen == null) {
            throw AppIssue("No interrupted or unmatched request to retry.")
        }
        prepare(frozen.json)
    }

    override suspend fun sign(taskId: String, password: SecretText, riskAcknowledged: Boolean) = withContext(main) {
        // AppStore.swift:412-458.
        if (current.busy || current.hasSignedResponse) throw AppIssue("This signing request is already being processed.")
        current.plan?.let { plan ->
            if (planWalletSnapshot != walletCollectionSnapshot() || plan.tasks.any { it.walletId in current.duplicateWalletIds }) {
                invalidateMatching()
                throw AppIssue("Wallet information changed. Match this request again.")
            }
        }
        val s = current
        val plan = s.plan
        val task = s.pendingTasks.firstOrNull()
        val frozen = pendingRequest
        val approved = s.review
        if (s.matchingIssue != null || plan == null || task == null || task.id != taskId || frozen == null ||
            approved == null || approved.requestHash != plan.requestHash ||
            s.publicWallets[task.walletId]?.walletKeyId != task.walletKeyId
        ) {
            throw AppIssue("No matching signing task. Review the request again.")
        }
        val epoch = s.securityEpoch
        val signingRequest = requestEpoch
        val operation = ++operationCounter
        signingOperation = operation
        _state.update { it.copy(busy = true) }
        try {
            requireSession(epoch, signingRequest)
            val request = frozen.json
            val result = authenticatedSignature(task, password, request, plan, epoch, signingRequest, riskAcknowledged)
            requireSession(epoch, signingRequest)
            if (result.str("requestHash") != plan.requestHash || result.str("planHash") != plan.planHash ||
                result.str("taskId") != taskId
            ) {
                throw AppIssue("Request changed. Review the transaction again.")
            }
            val frozenResult = withContext(default) { freeze(result) }
            val finalTask = current.pendingTasks.size == 1
            var frames = emptyList<String>()
            var text = ""
            var payload: QrPayloadView? = null
            if (finalTask) {
                val encoded = engineCall { engine.encodeResponse(request, result) }
                requireSession(epoch, signingRequest)
                frames = encoded.strings("frames") ?: emptyList()
                text = encoded.str("text") ?: frames.joinToString("\n")
                if (frames.isEmpty() && text.isEmpty()) throw AppIssue("Unable to encode signature response.")
                payload = QrPayloadView.parse(encoded)
            }
            previousResult = frozenResult.json
            _state.update {
                if (finalTask) {
                    it.copy(completedTaskIds = it.completedTaskIds + taskId, signatureFrames = frames, signatureText = text, signaturePayload = payload)
                } else {
                    it.copy(completedTaskIds = it.completedTaskIds + taskId)
                }
            }
            // What went in and what came out, both public: the canonical request the engine reviewed (so Activity can
            // review it again) and the response frames and text (so it can be shown and exported again).
            val receipt = SigningReceipt.create(
                nowMillis = clock.currentTimeMillis(),
                task = task,
                review = approved,
                planRequestHash = plan.requestHash,
                masterFingerprint = current.publicWallets[task.walletId]?.masterFingerprint,
                finalTask = finalTask,
                requestJson = frozen.text,
                signatureFrames = frames,
                signatureText = text,
            )
            withContext(NonCancellable) { appendReceipt(receipt) }
        } finally {
            if (signingOperation == operation) {
                signingOperation = null
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * AppStore.swift:463-472: the password opens the vault **before** any engine call; the secret lives only for this
     * one `signPlanTask` and is wiped afterwards (as is the seed). Nothing is cached for another operation.
     */
    private suspend fun authenticatedSignature(
        task: SigningTask,
        password: SecretText,
        request: JsonObject,
        plan: SigningPlan,
        epoch: Long,
        requestId: Long,
        riskAcknowledged: Boolean,
    ): JsonObject = withSecret(task.walletId, password) { secret ->
        requireSession(epoch, requestId)
        val seed = withContext(default) { bip39Seed.hex(secret.mnemonic, secret.passphrase) }
        try {
            engineCall { engine.signPlanTask(request, plan.raw, task.id, seed, riskAcknowledged, previousResult) }
        } finally {
            seed.wipe()
        }
    }

    // =============================================================================================================
    // Activity (AppStore.swift:532-601)

    override suspend fun clearHistory() = withContext(NonCancellable + main) {
        journal.withLock {
            try {
                receiptRepository.write(emptyList())
                _state.update { it.copy(receipts = emptyList(), activityUnreadable = false) }
            } catch (e: Exception) {
                reportReceiptWriteFailure()
            }
        }
    }

    override suspend fun reviewStoredRequest(json: String, expectedRequestHash: String): TransactionReview = withContext(main) {
        // AppStore.swift:581-591: a function of the request alone, no password, no matching, no epoch; the result must
        // still commit to the same fingerprint, which makes the stored copy self-checking.
        if (json.utf8Size() > SigningReceipt.PAYLOAD_LIMIT) throw AppIssue("The stored request could not be read.")
        val request = parseStoredRequest(json)
        val reviewed = TransactionReview.parse(engineCall { engine.review(request) })
        if (!reviewed.requestHash.equals(expectedRequestHash, ignoreCase = true) || reviewed.fields.isEmpty()) {
            throw AppIssue("The stored request does not match this signature's fingerprint.")
        }
        reviewed
    }

    private suspend fun loadReceipts() = journal.withLock {
        try {
            val records = receiptRepository.read()
            _state.update { it.copy(receipts = records, activityUnreadable = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(receipts = emptyList(), activityUnreadable = true, error = UiText.Resource(R.string.data_activity_unreadable))
            }
        }
    }

    /**
     * AppStore.swift:554-569 (rules in `ReceiptJournal.appended`). An unreadable journal is never overwritten with a
     * one-entry history: the read fails and the save error is reported instead.
     */
    private suspend fun appendReceipt(receipt: SigningReceipt) = journal.withLock {
        try {
            val existing = receiptRepository.read()
            val updated = withContext(default) { ReceiptJournal.appended(receipt, existing) }
            receiptRepository.write(updated)
            _state.update { it.copy(receipts = updated, activityUnreadable = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportReceiptWriteFailure()
        }
    }

    private fun reportReceiptWriteFailure() {
        setError(UiText.Resource(R.string.data_activity_not_saved))
    }

    // =============================================================================================================
    // Helpers

    /** The single translation point for engine errors (SignerStore KDoc "Errors"). */
    private suspend inline fun <T> engineCall(block: () -> T): T = try {
        block()
    } catch (e: EngineException) {
        throw EngineHostErrors.translate(e)
    }

    /** iOS `validateMnemonic` (`:171-175`): exact spellings, checksum verified, any of the ten languages. */
    private suspend fun canonical(mnemonic: SecretText): SecretText {
        currentCoroutineContext().ensureActive()
        return withContext(default) { SecretText.of(bip39.validatedCanonical(mnemonic.view()).phrase) }
    }

    /** `derivePublicIndex {seed}` with the native BIP39 seed; the seed is wiped right after the call. */
    private suspend fun derivePublicIndex(mnemonic: SecretText, passphrase: SecretText): JsonObject {
        val seed = withContext(default) { bip39Seed.hex(mnemonic, passphrase) }
        return try {
            engineCall { engine.derivePublicIndex(seed) }
        } finally {
            seed.wipe()
        }
    }

    /** Where [unlockInto] leaves the decrypted secret before control returns to a possibly cancelled caller. */
    private class SecretHolder {
        var secret: VaultSecret? = null
    }

    /**
     * A counted vault unlock that runs to completion and stores the secret in [holder] **inside** the non-cancellable
     * block: when the resumption into a cancelled caller throws, the holder still has it and the caller wipes it.
     */
    private suspend fun unlockInto(holder: SecretHolder, walletId: WalletId, password: SecretText) {
        withContext(NonCancellable + default) { holder.secret = vault.unlock(walletId, password) }
    }

    /** Unlocks [walletId] for [block] only; the secret is wiped afterwards in every case (AppStore.swift:463-472). */
    private suspend inline fun <R> withSecret(walletId: WalletId, password: SecretText, block: (VaultSecret) -> R): R {
        val holder = SecretHolder()
        try {
            unlockInto(holder, walletId, password)
            return block(checkNotNull(holder.secret))
        } finally {
            holder.secret?.close()
        }
    }

    /** iOS `frozen` + `object` (`:113-122`). */
    private fun freeze(raw: JsonObject): Frozen {
        if (!raw.isFiniteJson()) throw AppIssue("Invalid request format.")
        val text = raw.canonicalJson()
        if (text.utf8Size() > MAX_REQUEST_BYTES) throw AppIssue("Request exceeds the supported size.")
        val json = parseJsonObject(text) ?: throw AppIssue("Invalid local document.")
        return Frozen(json, text)
    }

    /**
     * A stored request (Activity): text that is not JSON cannot be read; JSON that is not an object is iOS
     * `object(data)`'s "Invalid local document.". (iOS shows the system parser message for the first case; Android shows
     * no platform exception text, ARCHITECTURE.md §4.2.)
     */
    private fun parseStoredRequest(json: String): JsonObject {
        val element = try {
            SignerJson.parseToJsonElement(json)
        } catch (e: Exception) {
            throw AppIssue("The stored request could not be read.")
        }
        return element as? JsonObject ?: throw AppIssue("Invalid local document.")
    }

    /** `JSONSerialization.isValidJSONObject`: numbers must be finite. */
    private fun JsonElement.isFiniteJson(): Boolean = when (this) {
        is JsonObject -> values.all { it.isFiniteJson() }
        is JsonArray -> all { it.isFiniteJson() }
        JsonNull -> true
        is JsonPrimitive -> isString || content !in NON_FINITE
    }

    companion object {
        /** Request, plan result and frozen-copy limit (`AppStore.swift:120`). */
        const val MAX_REQUEST_BYTES = 1_048_576
        /** Paste / file text limit (`AppStore.swift:355`). */
        const val MAX_TEXT_PAYLOAD_BYTES = 262_144
        /** Batch QR decode frame limit (`AppStore.swift:346`). */
        const val MAX_FRAMES = 2_048
        const val MAX_PASSPHRASE_BYTES = 1_024
        /** The Connect prefetch waits this long after it is allowed (`AppStore.swift:285`). */
        const val PREFETCH_DELAY_MILLIS = 600L
        /** iOS `walletLabel(for:)` for an unknown wallet (`AppStore.swift:151`). */
        private const val UNKNOWN_WALLET_LABEL = "Wallet"
        private val NON_FINITE = setOf("NaN", "Infinity", "-Infinity")
    }
}
