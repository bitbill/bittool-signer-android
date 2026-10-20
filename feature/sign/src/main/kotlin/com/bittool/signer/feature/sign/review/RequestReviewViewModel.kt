package com.bittool.signer.feature.sign.review

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.model.signing.MatchingIssue
import com.bittool.signer.core.model.signing.SigningTask
import com.bittool.signer.core.model.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A wallet as the review names it (iOS `walletLabel(for:)`, `fingerprint(for:)`, `avatarIdentity(for:)`,
 * `Models/AppStore.swift:151-159`): [name] null shows "Wallet", [fingerprint] (upper-case) null shows "Setup required".
 */
@Immutable
data class SigningWallet(val name: String?, val fingerprint: String?, val avatarIdentity: String) {
    companion object {
        fun of(state: SignerState, id: WalletId) =
            SigningWallet(state.displayName(id), state.fingerprint(id), state.avatarIdentity(id))
    }
}

/** One signing task with its wallet and whether it is signed (iOS `TaskCard` inputs). */
@Immutable
data class TaskItem(val task: SigningTask, val wallet: SigningWallet, val done: Boolean)

/**
 * The password prompt of one task (iOS `AuthorizeSheet`, `Features/Sign/RequestReviewView.swift:821-907`).
 * [riskAcknowledged] is the consent given when it was opened. [busy] while the signature is being made (the sheet
 * cannot be dismissed); [error] the failure to show (the field is refocused and the person retypes); [attempt] counts
 * failures so the screen can react to each; [closing] asks the sheet to animate away.
 */
@Immutable
data class AuthorizeUiState(
    val taskId: String,
    val wallet: SigningWallet,
    val riskAcknowledged: Boolean,
    val busy: Boolean = false,
    val error: UiText? = null,
    val attempt: Int = 0,
    val closing: Boolean = false,
)

/** What the review page shows. Built from `SignerState` plus this page's own state. */
@Immutable
data class RequestReviewUiState(
    val review: TransactionReview? = null,
    val matchingIssue: MatchingIssue? = null,
    /** Every task of the plan in plan order, or null while matching / when it cannot be signed. */
    val tasks: List<TaskItem>? = null,
    /** The next task to sign (tasks are signed strictly in plan order). */
    val pending: TaskItem? = null,
    val completedCount: Int = 0,
    /** The store is signing (Sign shows a spinner). */
    val busy: Boolean = false,
    val hasSignedResponse: Boolean = false,
    val riskAccepted: Boolean = false,
    val confirmReject: Boolean = false,
    val retrying: Boolean = false,
    val retryError: UiText? = null,
    val authorize: AuthorizeUiState? = null,
) {
    val taskCount: Int get() = tasks?.size ?: 0

    /** iOS `requiresAcknowledgement`: not fully decoded, or high risk. */
    val requiresAcknowledgement: Boolean get() = review?.requiresAcknowledgement == true

    /** Sign is blocked until the acknowledgement is ticked. */
    val blocked: Boolean get() = requiresAcknowledgement && !riskAccepted

    /** "Match Again" is offered for an interrupted or changed match while nothing is signed (`RequestReviewView.swift:95`). */
    val canRetry: Boolean get() = matchingIssue?.canRetry == true && tasks == null && !hasSignedResponse
}

private data class LocalState(
    val riskAccepted: Boolean = false,
    val confirmReject: Boolean = false,
    val retrying: Boolean = false,
    val retryError: UiText? = null,
    val authorize: AuthorizeUiState? = null,
)

/**
 * The request review and its per-task authorization (inventory rows 51–53; scan-sign.md §11, §13, §14, §18).
 *
 * Consent is per visit: [RequestReviewUiState.riskAccepted] is cleared by a new security epoch (which also closes the
 * password prompt and drops its late result), by a new request hash, by Reject and after **every** successful
 * signature (a multi-wallet request asks again for each wallet). Reject asks first when partial signatures exist.
 * Passwords are never held here beyond one [sign] call: the caller's [SecretText] is wiped when the call ends.
 */
@HiltViewModel
class RequestReviewViewModel @Inject constructor(private val store: SignerStore) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private var operation = 0L

    val uiState: StateFlow<RequestReviewUiState> = combine(store.state, local, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(store.state.value, local.value))

    init {
        viewModelScope.launch {
            store.state.map { it.securityEpoch }.distinctUntilChanged().drop(1).collect {
                // Background, App Lock or capture: consent and the password prompt end (iOS `.onChange(of: securityEpoch)`).
                operation++
                local.update { it.copy(riskAccepted = false, authorize = null) }
            }
        }
        viewModelScope.launch {
            store.state.map { it.review?.requestHash }.distinctUntilChanged().drop(1).collect {
                // Another request, or none (Reject, Done, deletion): iOS resets the consent on a new hash and discards
                // the whole page state with the page, so nothing of the previous request carries over.
                operation++
                local.value = LocalState()
            }
        }
    }

    private fun build(state: SignerState, local: LocalState): RequestReviewUiState {
        val tasks = state.plan?.tasks?.map { task ->
            TaskItem(task, SigningWallet.of(state, task.walletId), task.id in state.completedTaskIds)
        }
        val pending = state.pendingTasks.firstOrNull()?.let { first -> tasks?.firstOrNull { it.task.id == first.id } }
        return RequestReviewUiState(
            review = state.review,
            matchingIssue = state.matchingIssue,
            tasks = tasks,
            pending = pending,
            completedCount = state.completedTaskIds.size,
            busy = state.busy,
            hasSignedResponse = state.hasSignedResponse,
            riskAccepted = local.riskAccepted,
            confirmReject = local.confirmReject,
            retrying = local.retrying,
            retryError = local.retryError,
            authorize = local.authorize,
        )
    }

    // ---- Acknowledgement, reject, retry ------------------------------------------------------------------------

    fun toggleRisk() {
        local.update { it.copy(riskAccepted = !it.riskAccepted) }
    }

    /** "Reject" (`signing.reject`): immediate without partial signatures, otherwise "Discard partial signatures?". */
    fun reject() {
        if (store.state.value.completedTaskIds.isEmpty()) confirmReject() else local.update { it.copy(confirmReject = true) }
    }

    fun dismissReject() {
        local.update { it.copy(confirmReject = false) }
    }

    /** "Reject Request": the request, its plan and any partial signature are dropped. */
    fun confirmReject() {
        local.update { it.copy(riskAccepted = false, confirmReject = false) }
        store.clearRequest()
    }

    /** "Match Again" (`signing.retryMatch`): matches the frozen request again; no password is involved. */
    fun retryMatching() {
        if (local.value.retrying) return
        val epoch = store.state.value.securityEpoch
        local.update { it.copy(retrying = true, retryError = null) }
        viewModelScope.launch {
            try {
                store.retryMatching()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (e: Exception) {
                if (epoch == store.state.value.securityEpoch) local.update { it.copy(retryError = e.toUiText()) }
            } finally {
                local.update { it.copy(retrying = false) }
            }
        }
    }

    // ---- Authorization -----------------------------------------------------------------------------------------

    /** "Sign with …" (`signing.authorize`): opens the password prompt for the next task. */
    fun openAuthorize() {
        val state = build(store.state.value, local.value)
        val pending = state.pending ?: return
        if (state.matchingIssue != null || state.blocked || state.busy || state.authorize != null) return
        local.update { it.copy(authorize = AuthorizeUiState(pending.task.id, pending.wallet, it.riskAccepted)) }
    }

    /**
     * "Sign" (`authorize.sign`, iOS `AuthorizeSheet.sign`): [password] is owned by this call and wiped when it ends (the
     * caller has already cleared its field). On success the prompt closes and the consent is cleared; a failure for
     * the same session shows its message; a result for an older prompt (cancelled, backgrounded) is ignored.
     */
    fun sign(password: SecretText) {
        val authorize = local.value.authorize
        if (authorize == null || authorize.busy || authorize.closing || password.isEmpty()) {
            password.wipe()
            return
        }
        val current = ++operation
        val epoch = store.state.value.securityEpoch
        local.update { it.copy(authorize = it.authorize?.copy(busy = true, error = null)) }
        viewModelScope.launch {
            try {
                store.sign(authorize.taskId, password, authorize.riskAcknowledged)
                if (current != operation) return@launch
                local.update { it.copy(riskAccepted = false, authorize = it.authorize?.copy(busy = false, closing = true)) }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (current == operation) local.update { it.copy(authorize = it.authorize?.copy(busy = false)) }
            } catch (e: Exception) {
                if (current != operation) return@launch
                val shown = e.toUiText().takeIf { epoch == store.state.value.securityEpoch }
                local.update { state ->
                    state.copy(
                        authorize = state.authorize?.let {
                            if (shown == null) it.copy(busy = false) else it.copy(busy = false, error = shown, attempt = it.attempt + 1)
                        },
                    )
                }
            } finally {
                password.wipe()
            }
        }
    }

    /** "Cancel": the prompt closes; a signature still in flight is ignored here (the store applies a valid one). */
    fun cancelAuthorize() {
        operation++
        local.update { it.copy(authorize = it.authorize?.copy(busy = false, closing = true)) }
    }

    /** The prompt has left the screen (animation finished, swipe, Back). */
    fun onAuthorizeDismissed() {
        operation++
        local.update { it.copy(authorize = null) }
    }
}
