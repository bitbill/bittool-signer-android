package com.bittool.signer.feature.activity

import app.cash.turbine.test
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.Tab
import com.bittool.signer.feature.activity.ActivityTestData.receipt
import com.bittool.signer.feature.activity.ActivityTestData.utc
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The Activity list (iOS `ActivityTab`, activity-settings.md §2.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val zone = TimeZone.getDefault()

    @Before
    fun utcZone() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    private fun store(vararg receipts: com.bittool.signer.core.model.signing.SigningReceipt, unreadable: Boolean = false) =
        FakeSignerStore(
            SignerState(
                isLoading = false,
                walletLoadSucceeded = true,
                wallets = listOf(ActivityTestData.wallet),
                receipts = receipts.toList(),
                activityUnreadable = unreadable,
            ),
        )

    @Test
    fun receiptsAreGroupedByDayNewestFirst() = runTest {
        // ActivityTab.swift:8-12: days descending, receipts within a day descending.
        val store = store(
            receipt("00000000-0000-4000-8000-000000000001", utc(19, 23, 59)),
            receipt("00000000-0000-4000-8000-000000000002", utc(20, 8)),
            receipt("00000000-0000-4000-8000-000000000003", utc(20, 17)),
        )
        val viewModel = ActivityViewModel(store, AppRouter())
        val state = viewModel.uiState.first()
        assertEquals(listOf(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 19)), state.days.map { it.date })
        assertEquals(
            listOf("00000000-0000-4000-8000-000000000003", "00000000-0000-4000-8000-000000000002"),
            state.days[0].entries.map { it.id },
        )
        assertTrue(state.canClear)
    }

    @Test
    fun theDayFollowsTheDeviceTimeZone() {
        // 23:30 UTC on the 19th is already the 20th in Singapore.
        val receipts = listOf(receipt("00000000-0000-4000-8000-000000000001", utc(19, 23, 30)))
        assertEquals(LocalDate.of(2026, 10, 19), ActivityGrouping.receiptDays(receipts, ZoneOffset.UTC).single().first)
        assertEquals(LocalDate.of(2026, 10, 20), ActivityGrouping.receiptDays(receipts, ZoneId.of("Asia/Singapore")).single().first)
    }

    @Test
    fun recordsOfADeletedWalletSayDeletedWallet() = runTest {
        // Records survive wallet deletion (Privacy Policy §2); the label is the wallet's current name otherwise.
        val store = store(
            receipt("00000000-0000-4000-8000-000000000001"),
            receipt("00000000-0000-4000-8000-000000000002", walletId = com.bittool.signer.core.model.WalletId.random()),
            receipt("00000000-0000-4000-8000-000000000003", walletId = null),
        )
        val entries = ActivityViewModel(store, AppRouter()).uiState.first().days.single().entries.associateBy { it.id }
        assertEquals(WalletLabel.Named("Public fixture 1"), entries.getValue("00000000-0000-4000-8000-000000000001").wallet)
        assertEquals(WalletLabel.Deleted, entries.getValue("00000000-0000-4000-8000-000000000002").wallet)
        assertEquals(null, entries.getValue("00000000-0000-4000-8000-000000000003").wallet)
    }

    @Test
    fun noRecordsMeansNoClearButton() = runTest {
        val state = ActivityViewModel(store(), AppRouter()).uiState.first()
        assertFalse(state.canClear)
        assertFalse(state.unreadable)
        assertFalse(state.loading)
    }

    @Test
    fun anUnreadableJournalShowsTheExplanationWithoutClear() = runTest {
        // DECISIONS.md §10: iOS parity — no "Clear Activity" when the record file cannot be read.
        val state = ActivityViewModel(store(unreadable = true), AppRouter()).uiState.first()
        assertTrue(state.unreadable)
        assertFalse(state.canClear)
    }

    @Test
    fun clearAsksFirstAndThenClearsTheHistory() = runTest {
        val store = store(receipt("00000000-0000-4000-8000-000000000001"))
        val viewModel = ActivityViewModel(store, AppRouter())
        viewModel.uiState.test {
            assertFalse(awaitItem().showsClearConfirmation)
            viewModel.onClearClicked()
            assertTrue(awaitItem().showsClearConfirmation)
            viewModel.onClearConfirmed()
            advanceUntilIdle()
            val cleared = expectMostRecentItem()
            assertFalse(cleared.showsClearConfirmation)
            assertTrue(cleared.days.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, store.calls<FakeSignerStore.Call.ClearHistory>().size)
    }

    @Test
    fun theConfirmationIsDismissedWhenTheSecurityEpochChanges() = runTest {
        // ActivityTab.swift:58: background, App Lock and screen capture all renew the epoch.
        val store = store(receipt("00000000-0000-4000-8000-000000000001"))
        val viewModel = ActivityViewModel(store, AppRouter())
        viewModel.uiState.test {
            awaitItem()
            viewModel.onClearClicked()
            assertTrue(awaitItem().showsClearConfirmation)
            store.suspendSession()
            assertFalse(awaitItem().showsClearConfirmation)
            // A late confirmation from the stale dialog clears nothing.
            viewModel.onClearConfirmed()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(store.calls<FakeSignerStore.Call.ClearHistory>().isEmpty())
    }

    @Test
    fun cancellingKeepsTheRecords() = runTest {
        val store = store(receipt("00000000-0000-4000-8000-000000000001"))
        val viewModel = ActivityViewModel(store, AppRouter())
        viewModel.onClearClicked()
        viewModel.onClearDismissed()
        assertFalse(viewModel.uiState.first().showsClearConfirmation)
        assertTrue(store.calls<FakeSignerStore.Call.ClearHistory>().isEmpty())
    }

    @Test
    fun reselectingTheTabScrollsToTheTop() = runTest {
        val router = AppRouter()
        val viewModel = ActivityViewModel(store(), router)
        var scrolls = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.scrollToTop.collect { scrolls++ } }
        router.onTabShown(Tab.Activity)
        router.onTabClicked(Tab.Settings)
        router.onTabClicked(Tab.Activity)
        router.onTabClicked(Tab.Activity)
        advanceUntilIdle()
        assertEquals(1, scrolls, "only reselecting Activity scrolls the list to the top")
    }
}
