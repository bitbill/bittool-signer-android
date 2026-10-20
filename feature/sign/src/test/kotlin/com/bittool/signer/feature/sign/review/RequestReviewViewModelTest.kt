package com.bittool.signer.feature.sign.review

import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.signing.MatchingIssue
import com.bittool.signer.feature.sign.SignFixtures
import com.bittool.signer.feature.sign.SignFixtures.walletA
import com.bittool.signer.feature.sign.SignFixtures.walletB
import com.bittool.signer.feature.sign.tab.ScanStage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The review page's consent and per-task authorization (scan-sign.md §11.1, §11.7, §13, §18; iOS `RequestReviewView`
 * and `AuthorizeSheet`; gaps.md G1.8 steps 3–5, G1.9).
 */
class RequestReviewViewModelTest {
    private val store = FakeSignerStore(SignFixtures.walletsState())

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.review(): RequestReviewViewModel = RequestReviewViewModel(store).also { vm ->
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
    }

    private fun TestScope.reviewed(review: com.bittool.signer.core.model.review.TransactionReview, vararg tasks: Pair<String, com.bittool.signer.core.model.WalletId>) {
        store.setState { it.copy(review = review, plan = if (tasks.isEmpty()) null else SignFixtures.plan(*tasks)) }
        runCurrent()
    }

    private fun signCalls() = store.calls<FakeSignerStore.Call.Sign>()

    @Test
    fun aHighRiskRequestCannotBeSignedUntilAcknowledged() = runTest {
        val vm = review()
        reviewed(SignFixtures.knownSpenderApproval, "task-1" to walletA)
        val state = vm.uiState.value
        assertTrue(state.requiresAcknowledgement)
        assertTrue(state.blocked)
        assertEquals("Public fixture 1", state.pending?.wallet?.name, "\"Sign with Public fixture 1\"")
        vm.openAuthorize()
        assertNull(vm.uiState.value.authorize, "Sign is disabled")
        vm.toggleRisk()
        runCurrent()
        assertFalse(vm.uiState.value.blocked)
        vm.openAuthorize()
        runCurrent()
        val authorize = assertNotNull(vm.uiState.value.authorize)
        assertTrue(authorize.riskAcknowledged)
        assertEquals("73C5DA0A", authorize.wallet.fingerprint)
    }

    @Test
    fun aFullyDecodedNormalRequestNeedsNoAcknowledgement() = runTest {
        val vm = review()
        reviewed(SignFixtures.bitcoinSend, "task-1" to walletA)
        assertFalse(vm.uiState.value.requiresAcknowledgement)
        vm.openAuthorize()
        runCurrent()
        assertEquals(false, vm.uiState.value.authorize?.riskAcknowledged)
    }

    @Test
    fun consentIsPerVisitBackgroundingClosesThePromptAndUntick() = runTest {
        // G1.8 step 4: type the password, background and come back: the sheet is gone and the box is unticked.
        val vm = review()
        reviewed(SignFixtures.knownSpenderApproval, "task-1" to walletA)
        vm.toggleRisk()
        vm.openAuthorize()
        runCurrent()
        assertNotNull(vm.uiState.value.authorize)
        store.suspendSession()
        runCurrent()
        assertNull(vm.uiState.value.authorize)
        assertFalse(vm.uiState.value.riskAccepted)
        assertTrue(vm.uiState.value.blocked)
    }

    @Test
    fun aWrongPasswordIsShownAndTheNextTryCanSucceed() = runTest {
        // G1.8 step 5.
        store.onSign = { taskId, password, _ ->
            if (String(password.chars()) != "Public-Test-Password1!") throw com.bittool.signer.core.model.AppIssue("The password is incorrect.")
            store.setState { it.copy(completedTaskIds = it.completedTaskIds + taskId, signatureText = "UR:ETH-SIGNATURE/OK") }
        }
        val vm = review()
        reviewed(SignFixtures.knownSpenderApproval, "task-1" to walletA)
        vm.toggleRisk()
        vm.openAuthorize()
        runCurrent()
        val wrong = SecretText.of("Wrong-Password1!")
        vm.sign(wrong)
        runCurrent()
        val failed = assertNotNull(vm.uiState.value.authorize)
        assertEquals(UiText.Ios("The password is incorrect."), failed.error)
        assertEquals(1, failed.attempt, "the field is refocused")
        assertFalse(failed.busy)
        assertTrue(wrong.isEmpty(), "the password is wiped after the call")
        assertTrue(vm.uiState.value.riskAccepted, "a failure keeps the consent")

        vm.sign(SecretText.of("Public-Test-Password1!"))
        runCurrent()
        assertEquals(true, vm.uiState.value.authorize?.closing, "the sheet closes")
        assertFalse(vm.uiState.value.riskAccepted, "consent is cleared after every signature")
        assertEquals(listOf(true, true), signCalls().map { it.riskAcknowledged })
        assertEquals(listOf("task-1", "task-1"), signCalls().map { it.taskId })
        vm.onAuthorizeDismissed()
        runCurrent()
        assertNull(vm.uiState.value.authorize)
    }

    @Test
    fun eachWalletSignsWithItsOwnPasswordAndConsentInPlanOrder() = runTest {
        val vm = review()
        reviewed(SignFixtures.knownSpenderApproval, "task-a" to walletA, "task-b" to walletB)
        assertEquals(2, vm.uiState.value.taskCount)
        vm.toggleRisk()
        vm.openAuthorize()
        runCurrent()
        assertEquals("task-a", vm.uiState.value.authorize?.taskId)
        vm.sign(SecretText.of("first"))
        runCurrent()
        vm.onAuthorizeDismissed()
        runCurrent()
        val after = vm.uiState.value
        assertEquals(1, after.completedCount, "\"Signature 2 of 2\"")
        assertEquals(listOf(true, false), after.tasks?.map { it.done })
        assertEquals("Travel Signer", after.pending?.wallet?.name)
        assertTrue(after.blocked, "the acknowledgement must be ticked again for the second wallet")
        vm.toggleRisk()
        vm.openAuthorize()
        runCurrent()
        assertEquals("task-b", vm.uiState.value.authorize?.taskId)
    }

    @Test
    fun aResultForACancelledPromptIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        store.onSign = { _, _, _ -> gate.await(); throw com.bittool.signer.core.model.AppIssue("The password is incorrect.") }
        val vm = review()
        reviewed(SignFixtures.bitcoinSend, "task-1" to walletA)
        vm.openAuthorize()
        runCurrent()
        vm.sign(SecretText.of("typed"))
        runCurrent()
        assertEquals(true, vm.uiState.value.authorize?.busy, "the sheet cannot be dismissed while signing")
        vm.cancelAuthorize()
        runCurrent()
        assertEquals(true, vm.uiState.value.authorize?.closing)
        assertEquals(false, vm.uiState.value.authorize?.busy)
        gate.complete(Unit)
        runCurrent()
        assertNull(vm.uiState.value.authorize?.error, "the late failure is not shown")
    }

    @Test
    fun backgroundingWhileSigningDropsTheLateResultSilently() = runTest {
        val gate = CompletableDeferred<Unit>()
        store.onSign = { _, _, _ ->
            gate.await()
            throw CancellationException("The session changed; the late result is discarded.")
        }
        val vm = review()
        reviewed(SignFixtures.bitcoinSend, "task-1" to walletA)
        vm.openAuthorize()
        runCurrent()
        vm.sign(SecretText.of("typed"))
        runCurrent()
        store.suspendSession()
        gate.complete(Unit)
        runCurrent()
        assertNull(vm.uiState.value.authorize, "the prompt is closed and nothing is reported")
        assertNotNull(vm.uiState.value.review, "the review stays, so the person can sign again")
    }

    @Test
    fun rejectIsImmediateWithoutPartialSignatures() = runTest {
        // G1.9.
        val vm = review()
        reviewed(SignFixtures.bitcoinSend, "task-1" to walletA)
        vm.reject()
        runCurrent()
        assertFalse(vm.uiState.value.confirmReject)
        assertEquals(1, store.calls<FakeSignerStore.Call.ClearRequest>().size)
        assertNull(store.state.value.review)
    }

    @Test
    fun rejectAfterAPartialSignatureAsksFirst() = runTest {
        val vm = review()
        reviewed(SignFixtures.knownSpenderApproval, "task-a" to walletA, "task-b" to walletB)
        store.setState { it.copy(completedTaskIds = setOf("task-a")) }
        runCurrent()
        vm.reject()
        runCurrent()
        assertTrue(vm.uiState.value.confirmReject, "\"Discard partial signatures?\"")
        assertTrue(store.calls<FakeSignerStore.Call.ClearRequest>().isEmpty())
        vm.dismissReject()
        runCurrent()
        assertFalse(vm.uiState.value.confirmReject)
        vm.reject()
        vm.confirmReject()
        runCurrent()
        assertEquals(1, store.calls<FakeSignerStore.Call.ClearRequest>().size)
        assertFalse(vm.uiState.value.confirmReject)
    }

    @Test
    fun aNewRequestStartsWithoutThePreviousConsent() = runTest {
        val vm = review()
        reviewed(SignFixtures.knownSpenderApproval, "task-1" to walletA)
        vm.toggleRisk()
        runCurrent()
        store.setState { it.copy(review = SignFixtures.review(SignFixtures.knownSpenderApprovalJson("1" + SignFixtures.REQUEST_HASH.drop(1)))) }
        runCurrent()
        assertFalse(vm.uiState.value.riskAccepted)
    }

    @Test
    fun matchAgainIsOfferedForAnInterruptedMatchAndReportsFailures() = runTest {
        val vm = review()
        reviewed(SignFixtures.bitcoinSend)
        store.suspendSession()
        runCurrent()
        assertEquals(MatchingIssue.Interrupted, vm.uiState.value.matchingIssue)
        assertTrue(vm.uiState.value.canRetry)
        val gate = CompletableDeferred<Unit>()
        store.onRetryMatching = { gate.await(); throw com.bittool.signer.core.model.AppIssue("No interrupted or unmatched request to retry.") }
        vm.retryMatching()
        runCurrent()
        assertTrue(vm.uiState.value.retrying, "\"Matching…\"")
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.retrying)
        assertEquals(UiText.Ios("No interrupted or unmatched request to retry."), vm.uiState.value.retryError)
    }

    @Test
    fun matchAgainKeepsTheReviewOnScreenWhileTheStoreReviewsTheRequestAgain() = runTest {
        // The store's retry starts with clearRequest(); the page must not flash Home between two identical reviews.
        val vm = review()
        reviewed(SignFixtures.bitcoinSend)
        store.suspendSession()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        store.onRetryMatching = {
            store.clearRequest()
            gate.await()
            store.setState { it.copy(review = SignFixtures.bitcoinSend, plan = SignFixtures.plan("task-1" to walletA)) }
        }
        vm.retryMatching()
        runCurrent()
        assertNull(store.state.value.review, "the store has dropped the request for a moment")
        assertTrue(vm.retrying.value)
        assertTrue(vm.uiState.value.retrying, "still \"Matching…\"")
        assertEquals(ScanStage.Review, ScanStage.of(store.state.value).shown(vm.retrying.value), "Home is not shown")
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.retrying.value)
        assertEquals(ScanStage.Review, ScanStage.of(store.state.value).shown(vm.retrying.value))
        assertNotNull(vm.uiState.value.pending, "matched this time")
    }

    @Test
    fun aRetryThatLosesTheRequestLetsHomeShow() = runTest {
        val vm = review()
        reviewed(SignFixtures.bitcoinSend)
        store.suspendSession()
        runCurrent()
        store.onRetryMatching = {
            store.clearRequest()
            throw com.bittool.signer.core.model.AppIssue("The offline signing engine could not be started.")
        }
        vm.retryMatching()
        runCurrent()
        assertFalse(vm.retrying.value)
        assertEquals(ScanStage.Home, ScanStage.of(store.state.value).shown(vm.retrying.value))
    }

    @Test
    fun noMatchingWalletCannotBeRetried() = runTest {
        val vm = review()
        store.setState { it.copy(review = SignFixtures.bitcoinSend, matchingIssue = MatchingIssue.Engine("No matching wallet")) }
        runCurrent()
        assertFalse(vm.uiState.value.canRetry)
        assertNull(vm.uiState.value.pending, "\"Cannot Sign\"")
        store.setState { it.copy(matchingIssue = MatchingIssue.WalletsChanged) }
        runCurrent()
        assertTrue(vm.uiState.value.canRetry)
    }

    @Test
    fun aDeletedWalletIsNamedWalletAndAnIndexlessOneHasNoFingerprint() = runTest {
        val vm = review()
        reviewed(SignFixtures.bitcoinSend, "task-1" to walletA)
        store.setState { s -> s.copy(wallets = listOf(VaultMetadata(walletB, "Travel Signer", 2_000)), publicWallets = s.publicWallets - walletA) }
        runCurrent()
        val wallet = assertNotNull(vm.uiState.value.pending).wallet
        assertNull(wallet.name, "shown as \"Wallet\"")
        assertNull(wallet.fingerprint, "shown as \"Setup required\"")
        assertEquals(walletA.uppercase, wallet.avatarIdentity)
    }

    @Test
    fun aPageThatComesBackNeverStartsFromAnEarlierRequest() = runTest {
        // The review page leaves composition under the camera and on other tabs; the ViewModel stays (it belongs to
        // the Scan entry). Its state must follow the store meanwhile, or the next page's first frame shows the old one.
        val vm = RequestReviewViewModel(store)
        val page = backgroundScope.launch { vm.uiState.collect {} }
        reviewed(SignFixtures.knownSpenderApproval, "task-1" to walletA)
        page.cancel()
        store.clearRequest()
        advanceTimeBy(60_000)
        reviewed(SignFixtures.bitcoinSend)
        assertEquals(SignFixtures.bitcoinSend, vm.uiState.value.review)
        assertNull(vm.uiState.value.tasks, "still matching: no task of the earlier plan")
    }
}
