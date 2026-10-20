package com.bittool.signer.feature.wallets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.ConnectOptions
import com.bittool.signer.core.model.PairingLevel
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.feature.wallets.common.WalletIdentity
import com.bittool.signer.feature.wallets.connect.ConnectActions
import com.bittool.signer.feature.wallets.connect.ConnectContent
import com.bittool.signer.feature.wallets.connect.ConnectUiState
import com.bittool.signer.feature.wallets.detail.WalletDetailActions
import com.bittool.signer.feature.wallets.detail.WalletDetailContent
import com.bittool.signer.feature.wallets.detail.WalletDetailUiState
import com.bittool.signer.feature.wallets.list.WalletsActions
import com.bittool.signer.feature.wallets.list.WalletsContent
import com.bittool.signer.feature.wallets.list.WalletsUiState
import com.bittool.signer.feature.wallets.security.DeletePasswordDialog
import com.bittool.signer.feature.wallets.security.DeleteWalletActions
import com.bittool.signer.feature.wallets.security.DeleteWalletContent
import com.bittool.signer.feature.wallets.security.DeleteWalletUiState
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Screen contracts the e2e suite relies on (gaps.md G1.13–G1.16 test tags) and the iOS enabling rules. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp-xhdpi")
class WalletsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun content(body: @Composable () -> Unit) = compose.setContent {
        SignerTheme(darkTheme = false, applySystemBarAppearance = false) { body() }
    }

    private val identities = WalletFixtures.state(repair = true, duplicates = true).let { state ->
        state.wallets.map { WalletIdentity.of(state, it.id)!! }
    }

    @Test
    fun walletCardsCarryTheirIdsAndOnlyReadyWalletsOfferPairAndAddresses() {
        val opened = mutableListOf<String>()
        content {
            WalletsContent(
                uiState = WalletsUiState(identities, loaded = true),
                actions = WalletsActions(
                    onOpenWallet = { opened += "detail:$it" },
                    onPair = { opened += "pair:$it" },
                    onAddresses = { opened += "addresses:$it" },
                ),
            )
        }
        val ready = WalletFixtures.readyId
        val repair = WalletFixtures.repairId
        compose.onAllNodesWithText("Fingerprint 73C5DA0A").assertCountEquals(2)
        compose.onNodeWithText("Fingerprint Setup required").assertExists()
        compose.onNodeWithTag("wallet.connect.$ready").performClick()
        compose.onNodeWithTag("wallet.addresses.$ready").performClick()
        compose.onNodeWithTag("wallet.card.$ready").performClick()
        compose.onNodeWithTag("wallet.connect.$repair").assertDoesNotExist()
        compose.onNodeWithText("Repair wallet data").performClick()
        assertDuplicateNotices(2)
        assertEquals(listOf("pair:$ready", "addresses:$ready", "detail:$ready", "detail:$repair"), opened)
    }

    private fun assertDuplicateNotices(expected: Int) {
        val matches = compose.onAllNodes(
            SemanticsMatcher("duplicate notice") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Another wallet has the same key identity") } == true
            },
        ).fetchSemanticsNodes()
        assertEquals(expected, matches.size, "both copies of a duplicated key carry the warning")
    }

    @Test
    fun theAddWalletMenuStartsCreateOrImport() {
        val intents = mutableListOf<SetupIntent>()
        content { WalletsContent(WalletsUiState(identities.take(1), loaded = true), WalletsActions(onAddWallet = { intents += it })) }
        compose.onNodeWithTag("wallets.add").performClick()
        compose.onNodeWithText("Import Recovery Phrase").performClick()
        compose.onNodeWithTag("wallets.add").performClick()
        compose.onNodeWithText("Create New Wallet").performClick()
        assertEquals(listOf(SetupIntent.Restore, SetupIntent.Create), intents)
        compose.onNodeWithTag("wallets.scan").assertExists()
    }

    @Test
    fun theWalletPageListsEveryChainAndTheSecurityActions() {
        val chains = mutableListOf<Chain>()
        content {
            WalletDetailContent(
                uiState = WalletDetailUiState(walletId = WalletFixtures.readyId, identity = identities.first()),
                actions = WalletDetailActions(onConnect = { chains += it }),
                toast = rememberStatusToastState(),
                onCopyFingerprint = {},
                onRename = {},
            )
        }
        Chain.entries.forEach { compose.onNodeWithTag("detail.connect." + it.wire).performScrollTo().performClick() }
        assertEquals<List<Chain>>(Chain.entries, chains)
        listOf("detail.connect.wallet", "detail.addresses", "detail.recoveryPhrase", "detail.changePassword", "detail.rename", "detail.delete")
            .forEach { compose.onNodeWithTag(it).performScrollTo().assertExists() }
        compose.onNodeWithText("Added Oct 1, 2026", substring = true).assertExists()
    }

    @Test
    fun aWalletWithoutPublicDataOffersOnlyRepairAndSecurity() {
        val repaired = mutableListOf<Unit>()
        content {
            WalletDetailContent(
                uiState = WalletDetailUiState(walletId = WalletFixtures.repairId, identity = identities[1]),
                actions = WalletDetailActions(onRepair = { repaired += Unit }),
                toast = rememberStatusToastState(),
                onCopyFingerprint = {},
                onRename = {},
            )
        }
        compose.onNodeWithText("Repair needed").assertExists()
        compose.onNodeWithTag("detail.connect.bitcoin").assertDoesNotExist()
        compose.onNodeWithTag("detail.addresses").assertDoesNotExist()
        compose.onNodeWithTag("detail.repair").performClick()
        compose.onNodeWithTag("detail.recoveryPhrase").performScrollTo().assertExists()
        assertEquals(1, repaired.size)
    }

    @Test
    fun deletingNeedsTheConfirmationSwitchFirst() {
        var state by mutableStateOf(DeleteWalletUiState(identity = identities.first()))
        var deletes = 0
        content {
            DeleteWalletContent(
                uiState = state,
                toast = rememberStatusToastState(),
                actions = DeleteWalletActions(onConfirmedChange = { state = state.copy(confirmed = it) }, onDelete = { deletes++ }),
            )
        }
        compose.onNodeWithTag("vaultManagement.submit").assertIsNotEnabled()
        compose.onNodeWithText("Deleting asks for this wallet’s password.").assertExists()
        compose.onNodeWithTag("delete.backupConfirmed").performClick()
        compose.onNodeWithTag("vaultManagement.submit").assertIsEnabled().performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun theDeletePasswordFieldIsAddressedAsDeletePassword() {
        var entered: String? = null
        content { DeletePasswordDialog(onDelete = { secret: SecretText -> entered = String(secret.chars()); secret.wipe() }, onCancel = {}, onBlocked = {}) }
        compose.onNodeWithTag("delete.password").performTextInput("Public-Test-Password1!")
        compose.onNodeWithTag("delete.password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        compose.onNodeWithTag("delete.confirm").assertIsEnabled().performClick()
        assertEquals("Public-Test-Password1!", entered)
    }

    @Test
    fun walletLevelShowsTheAnimatedCodeAndTheWalletsThatReadIt() {
        val levels = mutableListOf<PairingLevel>()
        content {
            ConnectContent(
                uiState = ConnectUiState(level = PairingLevel.Wallet, wallet = WalletFixtures.walletPayload(), masterFingerprint = "73C5DA0A"),
                toast = rememberStatusToastState(),
                actions = ConnectActions(onSelectLevel = { levels += it }),
            )
        }
        compose.onNodeWithText("Pair Wallet").assertExists()
        compose.onNodeWithTag("connect.walletQR").assertExists()
        compose.onNodeWithTag("connect.copy").assertIsEnabled()
        compose.onNodeWithText("bittool-signer-wallet v1", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("BitTool Wallet").performScrollTo().assertExists()
        compose.onNodeWithText("Bitcoin · Taproot").performScrollTo().assertExists()
        compose.onNodeWithTag("connect.level.coin").performClick()
        assertEquals(listOf(PairingLevel.Coin), levels)
    }

    @Test
    fun coinLevelTronOffersTheIndexAndOnlyWatchOnlyWallets() {
        content {
            ConnectContent(
                uiState = ConnectUiState(
                    level = PairingLevel.Coin,
                    options = ConnectOptions(chain = Chain.Tron, tronIndex = 123_456),
                    coin = WalletFixtures.coinPayload(path = "m/44'/195'/0'/0/123456", address = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH", mode = "address"),
                    masterFingerprint = "73C5DA0A",
                ),
                toast = rememberStatusToastState(),
                actions = ConnectActions(),
            )
        }
        compose.onNodeWithText("Pair TRON").assertExists()
        compose.onNodeWithTag("connect.tronIndex.field").assertExists()
        compose.onNodeWithTag("pairing.outputMode").assertDoesNotExist()
        compose.onNodeWithText("Watch only").performScrollTo().assertExists()
        compose.onNodeWithText("Watch and sign by QR").assertDoesNotExist()
        compose.onNodeWithText("TokenPocket").performScrollTo().assertExists()
    }

    @Test
    fun coinLevelBitcoinOffersAccountTypeAddressTypeAndExportModes() {
        content {
            ConnectContent(
                uiState = ConnectUiState(level = PairingLevel.Coin, coin = WalletFixtures.coinPayload(), masterFingerprint = "73C5DA0A"),
                toast = rememberStatusToastState(),
                actions = ConnectActions(),
            )
        }
        compose.onNodeWithText("Pair Bitcoin").assertExists()
        listOf("connect.btcKind", "profile.menu", "pairing.outputMode", "connect.payloadToggle").forEach {
            compose.onNodeWithTag(it).performScrollTo().assertExists()
        }
        compose.onNodeWithText("Verify the first receive address").performScrollTo().assertExists()
        compose.onNodeWithText("m/84'/0'/0'/0/0").performScrollTo().assertExists()
        compose.onNodeWithText("ur:crypto-account · CBOR · 170 bytes").performScrollTo().assertExists()
        compose.onNodeWithText("Sparrow").performScrollTo().assertExists()
        compose.onNodeWithText("Watch and sign by QR").performScrollTo().assertExists()
        compose.onNodeWithTag("connect.payloadToggle").performScrollTo().performClick()
        compose.onNodeWithText("Fingerprints are stored as 32-bit numbers: 1942346250 is fingerprint 73C5DA0A.").performScrollTo().assertExists()
    }
}
