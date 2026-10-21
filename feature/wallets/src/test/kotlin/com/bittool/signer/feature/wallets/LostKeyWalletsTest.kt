package com.bittool.signer.feature.wallets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.feature.wallets.common.WalletIdentity
import com.bittool.signer.feature.wallets.detail.WalletDetailActions
import com.bittool.signer.feature.wallets.detail.WalletDetailContent
import com.bittool.signer.feature.wallets.detail.WalletDetailUiState
import com.bittool.signer.feature.wallets.list.WalletsActions
import com.bittool.signer.feature.wallets.list.WalletsContent
import com.bittool.signer.feature.wallets.list.WalletsUiState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA L4 (production-04, Android-only): a wallet without public data was always presented as "Repair needed", also when
 * its device key had been deleted with the screen lock (Repair then fails with "device key unavailable") or when the
 * device had no screen lock at all. Now the card and the wallet page say what is wrong and offer the one thing that
 * works: "Remove This Wallet" for a lost key, "Open Settings" for a missing screen lock, Repair only where it can work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp-xhdpi")
class LostKeyWalletsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun content(body: @Composable () -> Unit) = compose.setContent {
        SignerTheme(darkTheme = false, applySystemBarAppearance = false) { body() }
    }

    /** The fixture's ready wallet and its "Travel Signer" without public data, whose device key is gone. */
    private val lostKeyState: SignerState = WalletFixtures.state(repair = true).copy(
        metadataStatuses = mapOf(WalletFixtures.readyId to PublicMetadataStatus.Ready, WalletFixtures.repairId to PublicMetadataStatus.Invalid),
        lostKeyWalletIds = setOf(WalletFixtures.repairId),
    )

    private fun identities(state: SignerState) = state.wallets.map { WalletIdentity.of(state, it.id)!! }

    @Test
    fun theIdentityTellsALostKeyAMissingScreenLockAndARepairApart() {
        val lost = WalletIdentity.of(lostKeyState, WalletFixtures.repairId)!!
        assertTrue(lost.keyLost)
        assertFalse(lost.needsRepair)
        assertFalse(lost.screenLockRequired)

        val noLock = WalletIdentity.of(lostKeyState.copy(deviceSecure = false), WalletFixtures.repairId)!!
        assertTrue(noLock.screenLockRequired, "without a screen lock nothing can be unlocked: that comes first")
        assertFalse(noLock.keyLost)
        assertFalse(noLock.needsRepair)

        val repair = WalletIdentity.of(lostKeyState.copy(lostKeyWalletIds = emptySet()), WalletFixtures.repairId)!!
        assertTrue(repair.needsRepair)

        val ready = WalletIdentity.of(lostKeyState.copy(deviceSecure = false), WalletFixtures.readyId)!!
        assertFalse(ready.keyLost || ready.screenLockRequired || ready.needsRepair, "a ready wallet keeps its card")
    }

    @Test
    fun theCardOfALostKeyWalletOffersItsRemovalInsteadOfRepair() {
        val removed = mutableListOf<Any>()
        content {
            WalletsContent(
                uiState = WalletsUiState(identities(lostKeyState), loaded = true),
                actions = WalletsActions(onOpenWallet = { removed += "open" }, onRemove = { removed += it }),
            )
        }
        val id = WalletFixtures.repairId
        compose.onNodeWithText("This Wallet Can’t Be Unlocked").assertExists()
        compose.onNodeWithText("Repair wallet data").assertDoesNotExist()
        compose.onNodeWithText("Fingerprint Setup required").assertDoesNotExist()
        compose.onNodeWithTag("wallet.repair.$id").assertDoesNotExist()
        compose.onNodeWithTag("wallet.remove.$id").assertExists()
        compose.onNodeWithText("Remove This Wallet").performClick()
        assertEquals(listOf<Any>(id), removed)
        compose.onNodeWithTag("wallet.connect.${WalletFixtures.readyId}").assertExists()
    }

    @Test
    fun withoutAScreenLockTheCardOpensTheScreenLockSettings() {
        var opened = 0
        content {
            WalletsContent(
                uiState = WalletsUiState(identities(lostKeyState.copy(deviceSecure = false)), loaded = true),
                actions = WalletsActions(onSetScreenLock = { opened++ }),
            )
        }
        compose.onNodeWithText("Screen Lock Required").assertExists()
        compose.onNodeWithText("Repair wallet data").assertDoesNotExist()
        compose.onNodeWithTag("wallet.screenLock.${WalletFixtures.repairId}").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aWalletThatCanStillBeRepairedKeepsItsRepair() {
        val state = lostKeyState.copy(lostKeyWalletIds = emptySet())
        content { WalletsContent(uiState = WalletsUiState(identities(state), loaded = true), actions = WalletsActions()) }
        compose.onNodeWithTag("wallet.repair.${WalletFixtures.repairId}").assertExists()
        compose.onNodeWithText("Fingerprint Setup required").assertExists()
        compose.onNodeWithText("This Wallet Can’t Be Unlocked").assertDoesNotExist()
    }

    @Test
    fun theWalletPageOfALostKeyWalletOffersOnlyItsRemoval() {
        val removed = mutableListOf<Unit>()
        val repaired = mutableListOf<Unit>()
        content {
            WalletDetailContent(
                uiState = WalletDetailUiState(walletId = WalletFixtures.repairId, identity = WalletIdentity.of(lostKeyState, WalletFixtures.repairId)),
                actions = WalletDetailActions(onDelete = { removed += Unit }, onRepair = { repaired += Unit }),
                toast = rememberStatusToastState(),
                onCopyFingerprint = {},
                onRename = {},
            )
        }
        compose.onNodeWithTag("detail.deviceKeyLost").assertExists()
        compose.onNodeWithText("Repair needed").assertDoesNotExist()
        compose.onNodeWithTag("detail.repair").assertDoesNotExist()
        compose.onNodeWithText("Fingerprint Setup required").assertDoesNotExist()
        // No password can help any other action of this wallet.
        listOf("detail.recoveryPhrase", "detail.changePassword", "detail.rename", "detail.delete")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithTag("detail.removeLostKey").performScrollTo().performClick()
        assertEquals(1, removed.size, "the Delete page, which removes it without the password (B9)")
        assertTrue(repaired.isEmpty())
    }

    @Test
    fun theWalletPageWithoutAScreenLockAsksForOneInsteadOfARepair() {
        var opened = 0
        val repaired = mutableListOf<Unit>()
        val state = lostKeyState.copy(deviceSecure = false)
        content {
            WalletDetailContent(
                uiState = WalletDetailUiState(walletId = WalletFixtures.repairId, identity = WalletIdentity.of(state, WalletFixtures.repairId)),
                actions = WalletDetailActions(onRepair = { repaired += Unit }),
                toast = rememberStatusToastState(),
                onCopyFingerprint = {},
                onRename = {},
                onOpenLockSettings = { opened++ },
            )
        }
        compose.onNodeWithTag("detail.screenLockRequired").assertExists()
        compose.onNodeWithTag("detail.repair").assertDoesNotExist()
        compose.onNodeWithTag("detail.openLockSettings").performClick()
        assertEquals(1, opened)
        assertTrue(repaired.isEmpty())
        compose.onNodeWithTag("detail.delete").performScrollTo().assertExists()
    }
}
