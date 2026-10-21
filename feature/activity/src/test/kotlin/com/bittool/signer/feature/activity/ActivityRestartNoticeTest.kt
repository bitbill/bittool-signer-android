package com.bittool.signer.feature.activity

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.data.testing.FakeSignerStore.Call
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.feature.activity.ActivityTestData.receipt
import com.bittool.signer.feature.activity.ActivityTestData.utc
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA L1 (production-01, Android-only): the Activity record's Keystore key is deleted with the device's screen lock. The
 * store now starts a new record instead of "could not be read" on every load (`SignerState.activityRestarted`), and the
 * Activity tab tells the person once, until "OK".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp-xhdpi")
class ActivityRestartNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private fun store(restarted: Boolean, vararg receipts: com.bittool.signer.core.model.signing.SigningReceipt) = FakeSignerStore(
        SignerState(isLoading = false, walletLoadSucceeded = true, wallets = listOf(ActivityTestData.wallet), receipts = receipts.toList(), activityRestarted = restarted),
    )

    @Test
    fun theNoticeShowsUntilOkAndOnlyAfterARestart() = runTest {
        val store = store(restarted = true)
        val viewModel = ActivityViewModel(store, AppRouter())
        assertTrue(viewModel.uiState.first().restartNotice)
        viewModel.onRestartNoticeDismissed()
        assertEquals(listOf<Call>(Call.AcknowledgeActivityRestart), store.calls<Call.AcknowledgeActivityRestart>())
        assertFalse(viewModel.uiState.first { !it.restartNotice }.restartNotice)
        assertFalse(ActivityViewModel(store(restarted = false), AppRouter()).uiState.first().restartNotice)
    }

    @Test
    fun anEmptyRestartedRecordShowsTheNoticeAsItsEmptyState() {
        var dismissed = 0
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                ActivityContent(
                    state = ActivityUiState(loading = false, restartNotice = true),
                    onOpenReceipt = {},
                    onClear = {},
                    onConfirmClear = {},
                    onDismissClear = {},
                    onDismissRestartNotice = { dismissed++ },
                )
            }
        }
        compose.onNodeWithText("Activity Started Again").assertIsDisplayed()
        compose.onNodeWithText("No Signatures Yet").assertDoesNotExist()
        // The event that deletes the record's key (a removed screen lock) deletes the wallets' device keys too: the
        // notice must not tell the person their wallets are unaffected (review of L1).
        compose.onNodeWithText("A new record was started", substring = true).assertIsDisplayed()
        compose.onNodeWithText("wallets", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithTag(ActivityTags.RESTART_NOTICE_OK).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun aSignatureAfterTheRestartIsListedUnderTheNotice() {
        var dismissed = 0
        val entry = ActivityEntry("00000000-0000-4000-8000-0000000000aa", "Send bitcoin", "", null, utc(20, 9), com.bittool.signer.core.model.Chain.Bitcoin, null)
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                ActivityContent(
                    state = ActivityUiState(loading = false, days = listOf(ActivityDay(LocalDate.of(2026, 10, 20), listOf(entry))), restartNotice = true),
                    onOpenReceipt = {},
                    onClear = {},
                    onConfirmClear = {},
                    onDismissClear = {},
                    onDismissRestartNotice = { dismissed++ },
                )
            }
        }
        compose.onNodeWithTag(ActivityTags.RESTART_NOTICE).assertIsDisplayed()
        compose.onNodeWithTag(ActivityTags.ROW).assertExists()
        compose.onNodeWithTag(ActivityTags.RESTART_NOTICE_OK).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun anUnreadableRecordShowsItsOwnStateNotTheNotice() = runTest {
        val store = FakeSignerStore(
            SignerState(isLoading = false, walletLoadSucceeded = true, activityUnreadable = true, activityRestarted = true),
        )
        val state = ActivityViewModel(store, AppRouter()).uiState.first()
        assertTrue(state.unreadable)
        assertFalse(state.restartNotice)
    }

    @Test
    fun recordsKeepTheNoticeAboveThem() = runTest {
        val state = ActivityViewModel(store(true, receipt("00000000-0000-4000-8000-000000000001", utc(20, 8))), AppRouter()).uiState.first()
        assertTrue(state.restartNotice)
        assertEquals(1, state.days.single().entries.size)
    }
}
