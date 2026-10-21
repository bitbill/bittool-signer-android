package com.bittool.signer.root

import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.appearance.AppearanceController
import com.bittool.signer.core.data.PreferenceValues
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.testing.FakeSignerPreferences
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.model.Appearance
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.review.DecodeStatus
import com.bittool.signer.core.model.review.RiskLevel
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.navigation.Tab
import com.bittool.signer.core.navigation.WalletStackRequest
import com.bittool.signer.core.security.AppAccessState
import com.bittool.signer.core.security.ScenePhase
import com.bittool.signer.core.security.testing.FakeAppAccessController
import com.bittool.signer.core.security.testing.FakeAppLifecycleMonitor
import com.bittool.signer.core.security.testing.FakeWindowGate
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every RootView reaction (iOS RootView.swift:47-65, BitToolSignerApp.swift:40-43; app-shell.md §4.3; ARCHITECTURE
 * §2.1): wallet count → App Lock, count 0 → Wallets + reset, deletion epoch → reset, a new review → Scan, lock /
 * capture → suspend, prefetch only when visible, the window gate closed while locked or captured, the background
 * order, and the setup start with the introduction rule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RootViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = FakeSignerStore(SignerState(isLoading = true))
    private val access = FakeAppAccessController()
    private val preferences = FakeSignerPreferences(PreferenceValues())
    private val lifecycle = FakeAppLifecycleMonitor()
    private val gate = FakeWindowGate(open = false)
    private val router = AppRouter()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): RootViewModel {
        val appearance = AppearanceController(
            context = ApplicationProvider.getApplicationContext(),
            preferences = preferences,
            scope = CoroutineScope(dispatcher),
            main = dispatcher,
            uiTests = Optional.empty(),
        )
        return RootViewModel(store, access, preferences, lifecycle, gate, appearance, router)
    }

    private fun TestScope.stackRequests(): List<WalletStackRequest> {
        val requests = mutableListOf<WalletStackRequest>()
        backgroundScope.launch { router.walletStackRequests.toList(requests) }
        return requests
    }

    private fun wallet(name: String) = VaultMetadata(WalletId.random(), name, 1L)

    private fun review(hash: String) = TransactionReview(
        chain = Chain.Evm,
        title = TransactionReview.DEFAULT_TITLE,
        summary = "",
        fields = emptyList(),
        warnings = emptyList(),
        requestHash = hash,
        asset = null,
        decodeStatus = DecodeStatus.FullyDecoded,
        riskLevel = RiskLevel.Normal,
        network = null,
        display = null,
        contract = null,
    )

    private fun loaded(vararg names: String) {
        store.setState { it.copy(isLoading = false, walletLoadSucceeded = true, wallets = names.map(::wallet)) }
    }

    @Test
    fun theStageFollowsTheStore() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertEquals(RootStage.Loading, viewModel.uiState.value.stage)
        store.setState { it.copy(isLoading = false, walletLoadSucceeded = false) }
        assertEquals(RootStage.Failed, viewModel.uiState.value.stage)
        store.setState { it.copy(walletLoadSucceeded = true) }
        assertEquals(RootStage.Welcome, viewModel.uiState.value.stage)
        store.setState { it.copy(wallets = listOf(wallet("A"))) }
        assertEquals(RootStage.Main, viewModel.uiState.value.stage)
    }

    @Test
    fun theWalletCountReachesAppLockOnlyAfterASuccessfulLoad() = runTest(dispatcher) {
        viewModel()
        assertEquals(emptyList(), access.walletCounts)
        // A failed load is not an empty list: App Lock is never told 0 (it would switch itself off).
        store.setState { it.copy(isLoading = false, walletLoadSucceeded = false) }
        assertEquals(emptyList(), access.walletCounts)
        loaded("A", "B")
        assertEquals(listOf(2), access.walletCounts)
        store.setState { it.copy(wallets = it.wallets + wallet("C")) }
        assertEquals(listOf(2, 3), access.walletCounts)
    }

    @Test
    fun removingTheLastWalletSelectsWalletsAndResetsItsStack() = runTest(dispatcher) {
        val requests = stackRequests()
        val viewModel = viewModel()
        loaded("A")
        router.selectTab(Tab.Settings)
        store.setState { it.copy(wallets = emptyList()) }
        assertEquals(listOf(1, 0), access.walletCounts)
        assertEquals(Tab.Wallets, router.tab.value)
        assertEquals(listOf<WalletStackRequest>(WalletStackRequest.Reset), requests)
        assertEquals(RootStage.Welcome, viewModel.uiState.value.stage)
    }

    @Test
    fun aFirstLoadWithoutWalletsDoesNotResetNavigation() = runTest(dispatcher) {
        val requests = stackRequests()
        viewModel()
        loaded()
        assertEquals(listOf(0), access.walletCounts)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun aDeletedWalletResetsTheWalletsStack() = runTest(dispatcher) {
        val requests = stackRequests()
        viewModel()
        loaded("A", "B")
        store.setState { it.copy(navigationResetEpoch = it.navigationResetEpoch + 1) }
        assertEquals(listOf<WalletStackRequest>(WalletStackRequest.Reset), requests)
    }

    @Test
    fun aNewReviewSelectsTheScanTab() = runTest(dispatcher) {
        viewModel()
        loaded("A")
        router.selectTab(Tab.Activity)
        store.setState { it.copy(review = review("hash-1")) }
        assertEquals(Tab.Scan, router.tab.value)
        // Clearing the request selects nothing; the next request selects Scan again.
        router.selectTab(Tab.Settings)
        store.setState { it.copy(review = null) }
        assertEquals(Tab.Settings, router.tab.value)
        store.setState { it.copy(review = review("hash-2")) }
        assertEquals(Tab.Scan, router.tab.value)
    }

    @Test
    fun engagingAppLockOrScreenCaptureSuspendsTheSession() = runTest(dispatcher) {
        viewModel()
        loaded("A")
        store.clearCalls()
        access.lock()
        assertEquals(1, store.calls<FakeSignerStore.Call.SuspendSession>().size)
        store.clearCalls()
        lifecycle.setCaptured(true)
        assertEquals(1, store.calls<FakeSignerStore.Call.SuspendSession>().size)
    }

    @Test
    fun theBackgroundSuspendsBeforeAppLockRecordsTheTime() = runTest(dispatcher) {
        val viewModel = viewModel()
        store.clearCalls()
        viewModel.onEnterBackground()
        assertEquals(1, store.calls<FakeSignerStore.Call.SuspendSession>().size)
        assertEquals(1, access.backgroundCalls)
        viewModel.onEnterForeground()
        assertEquals(1, access.foregroundCalls)
        // QA L4: a screen lock removed or set in Settings meanwhile changes which wallets can still be unlocked.
        assertEquals(1, store.calls<FakeSignerStore.Call.RefreshDeviceKeys>().size)
    }

    @Test
    fun connectPrefetchRunsOnlyWhileTheTabsAreVisible() = runTest(dispatcher) {
        viewModel()
        fun prefetch() = store.calls<FakeSignerStore.Call.SetConnectPrefetchAllowed>().map { it.allowed }
        assertEquals(listOf(false), prefetch())
        loaded("A")
        assertEquals(listOf(false, true), prefetch())
        lifecycle.pause()
        assertEquals(listOf(false, true, false), prefetch())
        lifecycle.resume()
        access.lock()
        lifecycle.setCaptured(true)
        assertEquals(listOf(false, true, false, true, false), prefetch())
    }

    @Test
    fun theWindowGateIsClosedWhileLockedOrCaptured() = runTest(dispatcher) {
        access.mutableState.value = AppAccessState(resolving = true)
        val viewModel = viewModel()
        assertFalse(gate.isOpen.value, "closed while App Lock resolves (fail closed)")
        access.mutableState.value = AppAccessState(resolving = false, isEnabled = false, isLocked = false)
        assertTrue(gate.isOpen.value)
        assertTrue(viewModel.uiState.value.gateOpen)
        access.lock()
        assertFalse(gate.isOpen.value)
        assertTrue(viewModel.uiState.value.locked)
        access.mutableState.value = access.state.value.copy(isLocked = false)
        lifecycle.setCaptured(true)
        assertFalse(gate.isOpen.value)
        lifecycle.setCaptured(false)
        assertTrue(gate.isOpen.value)
    }

    @Test
    fun theCoverShowsWhenNotResumedOrCaptured() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertFalse(viewModel.uiState.value.coverVisible)
        lifecycle.pause()
        assertTrue(viewModel.uiState.value.coverVisible)
        lifecycle.resume()
        lifecycle.setCaptured(true)
        assertTrue(viewModel.uiState.value.coverVisible)
        assertTrue(viewModel.uiState.value.captured)
        assertEquals(ScenePhase.Active, lifecycle.phase.value)
    }

    @Test
    fun theIntroductionShowsOnlyBeforeTheFirstWalletUntilCompleted() = runTest(dispatcher) {
        val viewModel = viewModel()
        loaded()
        viewModel.startSetup(SetupIntent.Restore)
        assertEquals(SetupIntent.Restore, router.setup.value?.intent)
        assertEquals(true, router.setup.value?.showsIntroduction)
        viewModel.cancelSetup()
        assertNull(router.setup.value)

        viewModel.onIntroductionCompleted()
        assertEquals(1, preferences.values.value?.onboardingVersionCompleted)
        viewModel.startSetup(SetupIntent.Create)
        assertEquals(false, router.setup.value?.showsIntroduction)

        // With wallets the introduction never shows, completed or not.
        preferences.publish(PreferenceValues())
        loaded("A")
        viewModel.startSetup(SetupIntent.Create)
        assertEquals(false, router.setup.value?.showsIntroduction)
    }

    @Test
    fun okClearsTheGlobalError() = runTest(dispatcher) {
        val viewModel = viewModel()
        store.setError(UiText.Ios("The local activity record could not be read."))
        assertEquals(UiText.Ios("The local activity record could not be read."), viewModel.uiState.value.error)
        viewModel.dismissError()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun tryAgainReloadsOnce() = runTest(dispatcher) {
        val viewModel = viewModel()
        store.setState { it.copy(isLoading = false, walletLoadSucceeded = false) }
        store.clearCalls()
        viewModel.retryLoad()
        assertEquals(listOf<FakeSignerStore.Call>(FakeSignerStore.Call.Reload), store.calls<FakeSignerStore.Call.Reload>())
        assertFalse(viewModel.uiState.value.retrying)
        assertEquals(Appearance.System, viewModel.uiState.value.appearance)
    }
}
