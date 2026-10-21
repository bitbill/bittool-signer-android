package com.bittool.signer.core.data.testing

import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.ExistingWalletMatch
import com.bittool.signer.core.data.PreparedConnect
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.AppIssue
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
import com.bittool.signer.core.model.signing.MatchingIssue
import com.bittool.signer.core.model.util.trimWhitespacesAndNewlines
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/**
 * A scriptable [SignerStore] for ViewModel and screen tests (Wave 2). **Test double only**: never bound in production
 * (R8 removes it from release builds because nothing references it).
 *
 * - [state] is a plain [MutableStateFlow] the test sets with [setState] / [mutableState].
 * - Every call is recorded in [calls], in order. Secret arguments are recorded as copies so tests can assert what a
 *   ViewModel submitted (the caller still owns and wipes its own [SecretText]).
 * - Every operation runs a replaceable `on…` lambda. The defaults behave like a small in-memory store (create appends a
 *   wallet, rename renames it, delete removes it and renews the epochs, suspend renews `securityEpoch`…); operations
 *   whose results are engine documents (pairing payloads, stored reviews) fail with [NotScripted] until scripted.
 *   A lambda may suspend on a `CompletableDeferred` to hold an operation in flight, or throw to fail it.
 */
open class FakeSignerStore(initial: SignerState = SignerState(isLoading = false, walletLoadSucceeded = true)) : SignerStore {

    val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<SignerState> = mutableState.asStateFlow()

    private val recorded = CopyOnWriteArrayList<Call>()

    /** Every call so far, oldest first. */
    val calls: List<Call> get() = recorded.toList()

    /** The calls of type [T], oldest first. */
    inline fun <reified T : Call> calls(): List<T> = calls.filterIsInstance<T>()

    fun clearCalls() = recorded.clear()

    fun setState(transform: (SignerState) -> SignerState) = mutableState.update(transform)

    /** Prepared Connect QR per wallet, returned by [preparedConnect]. */
    var preparedConnects: Map<WalletId, PreparedConnect> = emptyMap()

    /** Thrown by an operation that has no scripted result. */
    class NotScripted(operation: String) : IllegalStateException("FakeSignerStore.$operation is not scripted")

    /** One recorded call. Secrets are copies taken at call time. */
    sealed interface Call {
        data object Reload : Call
        data object SuspendSession : Call
        data object BeginSensitiveSession : Call
        data object EndSensitiveSession : Call
        data class SetConnectPrefetchAllowed(val allowed: Boolean) : Call
        data class SetError(val text: UiText?) : Call
        data class GenerateMnemonic(val wordCount: Int) : Call
        data class CreateWallet(val name: String, val mnemonic: String, val passphrase: String, val password: String) : Call
        data class ExistingWallet(val mnemonic: String, val passphrase: String) : Call
        data class ResetPassword(val walletId: WalletId, val mnemonic: String, val passphrase: String, val newPassword: String) : Call
        data class CompleteSetup(val walletId: WalletId, val password: String) : Call
        data class RenameWallet(val walletId: WalletId, val name: String) : Call
        data class ChangePassword(val walletId: WalletId, val current: String, val new: String) : Call
        data class DeleteWallet(val walletId: WalletId, val password: String) : Call
        data class IsDeviceKeyLost(val walletId: WalletId) : Call
        data class RemoveWalletWithLostDeviceKey(val walletId: WalletId) : Call
        data class Reveal(val walletId: WalletId, val password: String) : Call
        data class Addresses(val walletId: WalletId, val chain: Chain, val profile: String, val start: Long, val count: Int) : Call
        data class Pairing(val walletId: WalletId, val chain: Chain, val profile: String, val outputMode: String, val index: Long) : Call
        data class WalletPairing(val walletId: WalletId) : Call
        data object BeginScan : Call
        data object EndScan : Call
        data class ReceiveFrame(val frame: String) : Call
        data class ReceiveFrames(val frames: List<String>) : Call
        data class ImportPayload(val text: String) : Call
        data class Prepare(val request: JsonObject) : Call
        data object RetryMatching : Call
        data class Sign(val taskId: String, val password: String, val riskAcknowledged: Boolean) : Call
        data object ClearRequest : Call
        data object ClearHistory : Call
        data class ReviewStoredRequest(val json: String, val expectedRequestHash: String) : Call
        data object RefreshDeviceKeys : Call
        data object AcknowledgeActivityRestart : Call
    }

    // ---- Scriptable behaviour -----------------------------------------------------------------------------------

    var onReload: suspend () -> Unit = { setState { it.copy(isLoading = false) } }
    var onGenerateMnemonic: suspend (wordCount: Int) -> SecretText = { count ->
        when (count) {
            12 -> SecretText.of(PUBLIC_FIXTURE_12)
            24 -> SecretText.of(PUBLIC_FIXTURE_24)
            else -> throw AppIssue("Unable to generate recovery phrase.")
        }
    }
    var onCreateWallet: suspend (name: String, mnemonic: SecretText, passphrase: SecretText, password: SecretText) -> VaultMetadata =
        { name, _, _, _ ->
            val created = VaultMetadata(WalletId.random(), name.trimWhitespacesAndNewlines(), System.currentTimeMillis())
            setState { it.copy(wallets = it.wallets + created) }
            created
        }
    var onExistingWallet: suspend (mnemonic: SecretText, passphrase: SecretText) -> WalletId? = { _, _ -> null }

    /**
     * [findExistingWallet] (QA L3; recorded as [Call.ExistingWallet] like [existingWallet]): by default [onExistingWallet]'s
     * answer as a verified match.
     */
    var onFindExistingWallet: suspend (mnemonic: SecretText, passphrase: SecretText) -> ExistingWalletMatch? = { mnemonic, passphrase ->
        onExistingWallet(mnemonic, passphrase)?.let { ExistingWalletMatch(it, verified = true) }
    }
    var onRefreshDeviceKeys: suspend () -> Unit = {}
    var onAcknowledgeActivityRestart: suspend () -> Unit = { setState { it.copy(activityRestarted = false) } }
    var onResetPassword: suspend (walletId: WalletId, mnemonic: SecretText, passphrase: SecretText, newPassword: SecretText) -> Unit =
        { _, _, _, _ -> suspendSession() }
    var onCompleteSetup: suspend (walletId: WalletId, password: SecretText) -> Unit = { _, _ -> }
    var onRenameWallet: suspend (walletId: WalletId, name: String) -> Unit = { id, name ->
        val trimmed = name.trimWhitespacesAndNewlines()
        setState { s -> s.copy(wallets = s.wallets.map { if (it.id == id) it.copy(name = trimmed) else it }) }
    }
    var onChangePassword: suspend (walletId: WalletId, current: SecretText, new: SecretText) -> Unit = { _, _, _ -> }
    var onDeleteWallet: suspend (walletId: WalletId, password: SecretText) -> Unit = { id, _ -> removeFromState(id) }

    /** Wallets whose device key is provably gone: the default [onIsDeviceKeyLost] / [onRemoveWalletWithLostDeviceKey]. */
    var lostDeviceKeys: Set<WalletId> = emptySet()
    var onIsDeviceKeyLost: suspend (walletId: WalletId) -> Boolean = { it in lostDeviceKeys }
    var onRemoveWalletWithLostDeviceKey: suspend (walletId: WalletId) -> Unit = { id ->
        if (id !in lostDeviceKeys) throw VaultError.DeviceKeyLossUnconfirmed()
        lostDeviceKeys = lostDeviceKeys - id
        removeFromState(id)
    }
    var onReveal: suspend (walletId: WalletId, password: SecretText) -> VaultSecret = { _, _ ->
        VaultSecret(SecretText.of(PUBLIC_FIXTURE_12), SecretText.empty())
    }
    var onAddresses: suspend (walletId: WalletId, chain: Chain, profile: String, start: Long, count: Int) -> List<PublicAccount> =
        { _, _, _, _, _ -> emptyList() }
    var onPairing: suspend (walletId: WalletId, chain: Chain, profile: String, outputMode: String, index: Long) -> PairingPayload =
        { _, _, _, _, _ -> throw NotScripted("pairing") }
    var onWalletPairing: suspend (walletId: WalletId) -> WalletPairingPayload = { throw NotScripted("walletPairing") }
    var onBeginScan: suspend () -> Unit = {}
    var onEndScan: suspend () -> Unit = {}
    var onReceiveFrame: suspend (frame: String) -> Double = { 0.0 }
    var onReceiveFrames: suspend (frames: List<String>) -> Double = { 0.0 }
    var onImportPayload: suspend (text: String) -> Unit = {}
    var onPrepare: suspend (request: JsonObject) -> Unit = {}
    var onRetryMatching: suspend () -> Unit = {}
    var onSign: suspend (taskId: String, password: SecretText, riskAcknowledged: Boolean) -> Unit = { taskId, _, _ ->
        setState { it.copy(completedTaskIds = it.completedTaskIds + taskId) }
    }
    var onClearHistory: suspend () -> Unit = { setState { it.copy(receipts = emptyList(), activityUnreadable = false) } }
    var onReviewStoredRequest: suspend (json: String, expectedRequestHash: String) -> TransactionReview =
        { _, _ -> throw NotScripted("reviewStoredRequest") }

    // ---- SignerStore ------------------------------------------------------------------------------------------

    override suspend fun reload() {
        recorded += Call.Reload
        onReload()
    }

    override fun suspendSession() {
        recorded += Call.SuspendSession
        setState { s ->
            val interrupted = s.review != null && s.plan == null && s.matchingIssue == null
            s.copy(
                securityEpoch = s.securityEpoch + 1,
                busy = false,
                matchingIssue = if (interrupted) MatchingIssue.Interrupted else s.matchingIssue,
            )
        }
    }

    override fun beginSensitiveSession() {
        recorded += Call.BeginSensitiveSession
    }

    override fun endSensitiveSession() {
        recorded += Call.EndSensitiveSession
    }

    override fun setConnectPrefetchAllowed(allowed: Boolean) {
        recorded += Call.SetConnectPrefetchAllowed(allowed)
    }

    override fun preparedConnect(walletId: WalletId): PreparedConnect? = preparedConnects[walletId]

    override fun setError(text: UiText?) {
        recorded += Call.SetError(text)
        setState { it.copy(error = text) }
    }

    override suspend fun generateMnemonic(wordCount: Int): SecretText {
        recorded += Call.GenerateMnemonic(wordCount)
        return onGenerateMnemonic(wordCount)
    }

    override suspend fun createWallet(name: String, mnemonic: SecretText, passphrase: SecretText, password: SecretText): VaultMetadata {
        recorded += Call.CreateWallet(name, mnemonic.text(), passphrase.text(), password.text())
        return onCreateWallet(name, mnemonic, passphrase, password)
    }

    override suspend fun existingWallet(mnemonic: SecretText, passphrase: SecretText): WalletId? {
        recorded += Call.ExistingWallet(mnemonic.text(), passphrase.text())
        return onExistingWallet(mnemonic, passphrase)
    }

    override suspend fun findExistingWallet(mnemonic: SecretText, passphrase: SecretText): ExistingWalletMatch? {
        recorded += Call.ExistingWallet(mnemonic.text(), passphrase.text())
        return onFindExistingWallet(mnemonic, passphrase)
    }

    override suspend fun refreshDeviceKeys() {
        recorded += Call.RefreshDeviceKeys
        onRefreshDeviceKeys()
    }

    override suspend fun acknowledgeActivityRestart() {
        recorded += Call.AcknowledgeActivityRestart
        onAcknowledgeActivityRestart()
    }

    override suspend fun resetPassword(walletId: WalletId, mnemonic: SecretText, passphrase: SecretText, newPassword: SecretText) {
        recorded += Call.ResetPassword(walletId, mnemonic.text(), passphrase.text(), newPassword.text())
        onResetPassword(walletId, mnemonic, passphrase, newPassword)
    }

    override suspend fun completeSetup(walletId: WalletId, password: SecretText) {
        recorded += Call.CompleteSetup(walletId, password.text())
        onCompleteSetup(walletId, password)
    }

    override suspend fun renameWallet(walletId: WalletId, name: String) {
        recorded += Call.RenameWallet(walletId, name)
        onRenameWallet(walletId, name)
    }

    override suspend fun changePassword(walletId: WalletId, current: SecretText, new: SecretText) {
        recorded += Call.ChangePassword(walletId, current.text(), new.text())
        onChangePassword(walletId, current, new)
    }

    override suspend fun deleteWallet(walletId: WalletId, password: SecretText) {
        recorded += Call.DeleteWallet(walletId, password.text())
        onDeleteWallet(walletId, password)
    }

    override suspend fun isDeviceKeyLost(walletId: WalletId): Boolean {
        recorded += Call.IsDeviceKeyLost(walletId)
        return onIsDeviceKeyLost(walletId)
    }

    override suspend fun removeWalletWithLostDeviceKey(walletId: WalletId) {
        recorded += Call.RemoveWalletWithLostDeviceKey(walletId)
        onRemoveWalletWithLostDeviceKey(walletId)
    }

    override suspend fun reveal(walletId: WalletId, password: SecretText): VaultSecret {
        recorded += Call.Reveal(walletId, password.text())
        return onReveal(walletId, password)
    }

    override suspend fun addresses(walletId: WalletId, chain: Chain, profile: String, start: Long, count: Int): List<PublicAccount> {
        recorded += Call.Addresses(walletId, chain, profile, start, count)
        return onAddresses(walletId, chain, profile, start, count)
    }

    override suspend fun pairing(walletId: WalletId, chain: Chain, profile: String, outputMode: String, index: Long): PairingPayload {
        recorded += Call.Pairing(walletId, chain, profile, outputMode, index)
        return onPairing(walletId, chain, profile, outputMode, index)
    }

    override suspend fun walletPairing(walletId: WalletId): WalletPairingPayload {
        recorded += Call.WalletPairing(walletId)
        return onWalletPairing(walletId)
    }

    override suspend fun beginScan() {
        recorded += Call.BeginScan
        onBeginScan()
    }

    override suspend fun endScan() {
        recorded += Call.EndScan
        onEndScan()
    }

    override suspend fun receive(frame: String): Double {
        recorded += Call.ReceiveFrame(frame)
        return onReceiveFrame(frame)
    }

    override suspend fun receive(frames: List<String>): Double {
        recorded += Call.ReceiveFrames(frames)
        return onReceiveFrames(frames)
    }

    override suspend fun importPayload(text: String) {
        recorded += Call.ImportPayload(text)
        onImportPayload(text)
    }

    override suspend fun prepare(request: JsonObject) {
        recorded += Call.Prepare(request)
        onPrepare(request)
    }

    override suspend fun retryMatching() {
        recorded += Call.RetryMatching
        onRetryMatching()
    }

    override suspend fun sign(taskId: String, password: SecretText, riskAcknowledged: Boolean) {
        recorded += Call.Sign(taskId, password.text(), riskAcknowledged)
        onSign(taskId, password, riskAcknowledged)
    }

    override fun clearRequest() {
        recorded += Call.ClearRequest
        setState {
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

    override suspend fun clearHistory() {
        recorded += Call.ClearHistory
        onClearHistory()
    }

    override suspend fun reviewStoredRequest(json: String, expectedRequestHash: String): TransactionReview {
        recorded += Call.ReviewStoredRequest(json, expectedRequestHash)
        return onReviewStoredRequest(json, expectedRequestHash)
    }

    private fun SecretText.text(): String = String(chars())

    /** What a deletion commits (AppStore.swift:526-530): suspend, clear the request, drop the wallet, reset navigation. */
    private fun removeFromState(id: WalletId) {
        suspendSession()
        clearRequest()
        setState { s ->
            s.copy(
                wallets = s.wallets.filterNot { it.id == id },
                publicWallets = s.publicWallets - id,
                metadataStatuses = s.metadataStatuses - id,
                lostKeyWalletIds = s.lostKeyWalletIds - id,
                navigationResetEpoch = s.navigationResetEpoch + 1,
            )
        }
    }

    companion object {
        /** The published BIP39 test phrase (never fund it). */
        const val PUBLIC_FIXTURE_12 =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        /** The published 24-word BIP39 test phrase (entropy 00…00; never fund it). */
        const val PUBLIC_FIXTURE_24 = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
    }
}
