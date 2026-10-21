package com.bittool.signer.feature.wallets

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.ConnectOptions
import com.bittool.signer.core.model.PairingLevel
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.feature.wallets.common.WalletIdentity
import com.bittool.signer.feature.wallets.connect.ConnectActions
import com.bittool.signer.feature.wallets.connect.ConnectContent
import com.bittool.signer.feature.wallets.connect.ConnectUiState
import com.bittool.signer.feature.wallets.detail.RenameWalletDialog
import com.bittool.signer.feature.wallets.detail.WalletDetailActions
import com.bittool.signer.feature.wallets.detail.WalletDetailContent
import com.bittool.signer.feature.wallets.detail.WalletDetailScreen
import com.bittool.signer.feature.wallets.detail.WalletDetailUiState
import com.bittool.signer.feature.wallets.detail.WalletDetailViewModel
import com.bittool.signer.feature.wallets.list.WalletsActions
import com.bittool.signer.feature.wallets.list.WalletsContent
import com.bittool.signer.feature.wallets.list.WalletsUiState
import com.bittool.signer.feature.wallets.security.ChangePasswordContent
import com.bittool.signer.feature.wallets.security.DeletePasswordDialog
import com.bittool.signer.feature.wallets.security.DeleteWalletActions
import com.bittool.signer.feature.wallets.security.DeleteWalletContent
import com.bittool.signer.feature.wallets.security.DeleteWalletUiState
import com.bittool.signer.feature.wallets.security.PasswordFields
import com.bittool.signer.feature.wallets.security.RepairWalletContent
import com.bittool.signer.feature.wallets.security.SecurityPageUiState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
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

    /**
     * QA wallets-08 (`WalletDetailView.swift:137` `VStack(spacing: 4)`): name, fingerprint and "Added" stay 4 dp apart;
     * the fingerprint is laid out at its line's height, so the header is not loosened by a 48 dp row. Accessibility
     * services still get a 48 dp target, and a touch in the gaps around the line still copies (Compose's minimum touch
     * target).
     */
    @Test
    fun theHeaderStacksTightlyAndTheFingerprintStillTakesTouchesAroundIt() {
        var copies = 0
        content {
            WalletDetailContent(
                uiState = WalletDetailUiState(walletId = WalletFixtures.readyId, identity = identities.first()),
                actions = WalletDetailActions(),
                toast = rememberStatusToastState(),
                onCopyFingerprint = { copies++ },
                onRename = {},
            )
        }
        val fingerprint = compose.onNodeWithTag("detail.fingerprint").getBoundsInRoot()
        val added = compose.onNodeWithText("Added Oct 1, 2026", substring = true).getBoundsInRoot()
        assertTrue(fingerprint.height < 24.dp, "one footnote line, not a 48 dp row: ${fingerprint.height}")
        assertEquals(4.dp, added.top - fingerprint.bottom, "iOS VStack spacing 4")
        // What TalkBack and Switch Access get (the node's touch bounds, AccessibilityNodeInfo.boundsInScreen).
        val touchHeight = with(compose.density) {
            compose.onNodeWithTag("detail.fingerprint").fetchSemanticsNode().touchBoundsInRoot.height.toDp()
        }
        assertTrue(touchHeight >= 48.dp, "a 48 dp accessibility target: $touchHeight")
        compose.onNodeWithTag("detail.fingerprint").performTouchInput { click(Offset(centerX, -1.dp.toPx())) }
        compose.onNodeWithTag("detail.fingerprint").performTouchInput { click(Offset(centerX, height + 1.dp.toPx())) }
        // Beside the date, which is narrower than the fingerprint line: nothing else is there.
        compose.onNodeWithTag("detail.fingerprint").performTouchInput { click(Offset(2.dp.toPx(), height + 10.dp.toPx())) }
        assertEquals(3, copies, "touches in the gaps around the line and beside the date copy the fingerprint")
    }

    /**
     * QA regression-09 (`WalletDetailView.swift:141-153`: only the fingerprint button copies): the fingerprint's
     * expanded touch target covered most of the "Added …" line and the lower half of the name, so a tap on the date
     * copied the fingerprint and showed "Copied".
     */
    @Test
    fun tappingTheDateOrTheNameCopiesNothing() {
        var copies = 0
        content {
            WalletDetailContent(
                uiState = WalletDetailUiState(walletId = WalletFixtures.readyId, identity = identities.first()),
                actions = WalletDetailActions(),
                toast = rememberStatusToastState(),
                onCopyFingerprint = { copies++ },
                onRename = {},
            )
        }
        val added = compose.onNodeWithText("Added Oct 1, 2026", substring = true)
        added.performTouchInput { click() }
        added.performTouchInput { click(Offset(centerX, 2.dp.toPx())) }
        assertEquals(0, copies, "the date is not the fingerprint")
        // 8 dp above the fingerprint line: 4 dp inside the name's line, well inside the old 48 dp expansion.
        compose.onNodeWithTag("detail.fingerprint").performTouchInput { click(Offset(centerX, -8.dp.toPx())) }
        assertEquals(0, copies, "the name is not the fingerprint")
        compose.onNodeWithTag("detail.fingerprint").performClick()
        assertEquals(1, copies)
    }

    /**
     * QA wallets-10 (`WalletSecuritySheets.swift:169, 248, 327`): Change Password, Delete Wallet and Repair Wallet close
     * with the cross read "Cancel", the iOS button, like the Recovery Phrase page; the e2e tag stays `nav.close`.
     */
    @Test
    fun theSecurityPagesCloseWithCancel() {
        var page by mutableStateOf(0)
        content {
            val identity = identities.first()
            when (page) {
                0 -> ChangePasswordContent(
                    uiState = SecurityPageUiState(identity = identity),
                    fields = remember { PasswordFields(3) },
                    toast = rememberStatusToastState(),
                    onSubmit = {},
                    onCancel = {},
                )
                1 -> DeleteWalletContent(uiState = DeleteWalletUiState(identity = identity), toast = rememberStatusToastState(), actions = DeleteWalletActions())
                else -> RepairWalletContent(
                    uiState = SecurityPageUiState(identity = identity),
                    fields = remember { PasswordFields(1) },
                    toast = rememberStatusToastState(),
                    onSubmit = {},
                    onCancel = {},
                )
            }
        }
        repeat(3) {
            page = it
            compose.onNodeWithTag("nav.close").assertContentDescriptionEquals("Cancel")
        }
    }

    /**
     * QA backlog B22: until the device key check answers, Delete Wallet shows the wallet and a neutral loading state,
     * neither the password path's statement nor "This Wallet Can’t Be Unlocked", and nothing can be confirmed.
     */
    @Test
    fun deleteShowsANeutralStateUntilTheDeviceKeyCheckAnswers() {
        var state by mutableStateOf(DeleteWalletUiState(identity = identities.first(), checkingDeviceKey = true))
        content { DeleteWalletContent(uiState = state, toast = rememberStatusToastState(), actions = DeleteWalletActions()) }
        compose.onNodeWithTag("delete.checking")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
        compose.onNodeWithText("Public fixture 1", substring = true).assertExists()
        listOf("delete.deviceKeyLost", "delete.backupConfirmed", "vaultManagement.submit").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithText("Delete this wallet?").assertDoesNotExist()
        compose.onNodeWithText("This Wallet Can’t Be Unlocked").assertDoesNotExist()
        compose.onNodeWithTag("nav.close").assertIsEnabled()

        state = state.copy(checkingDeviceKey = false)
        compose.onNodeWithTag("delete.checking").assertDoesNotExist()
        compose.onNodeWithTag("delete.backupConfirmed").assertExists()
        compose.onNodeWithTag("vaultManagement.submit").assertIsNotEnabled()
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
    fun aWalletWithALostDeviceKeyIsRemovedThroughItsOwnConfirmations() {
        // QA backlog B9: the page explains the lost key, keeps the switch, and ends in "Remove This Wallet?".
        var state by mutableStateOf(DeleteWalletUiState(identity = identities.first(), deviceKeyLost = true))
        var removals = 0
        content {
            DeleteWalletContent(
                uiState = state,
                toast = rememberStatusToastState(),
                actions = DeleteWalletActions(
                    onConfirmedChange = { state = state.copy(confirmed = it) },
                    onDelete = { state = state.copy(askingRemoval = true) },
                    onRemove = { removals++ },
                    onCancelRemoval = { state = state.copy(askingRemoval = false) },
                ),
            )
        }
        compose.onNodeWithTag("delete.deviceKeyLost").assertExists()
        compose.onNodeWithText("This Wallet Can’t Be Unlocked").assertExists()
        compose.onNodeWithText("Deleting asks for this wallet’s password.").assertDoesNotExist()
        compose.onNodeWithText("Remove This Wallet").assertExists()
        compose.onNodeWithTag("vaultManagement.submit").assertIsNotEnabled()
        compose.onNodeWithTag("delete.backupConfirmed").performClick()
        compose.onNodeWithTag("vaultManagement.submit").assertIsEnabled().performClick()
        compose.onNodeWithText("Remove This Wallet?").assertExists()
        compose.onNodeWithTag("delete.password").assertDoesNotExist()
        compose.onNodeWithTag("delete.cancel").performClick()
        compose.onNodeWithText("Remove This Wallet?").assertDoesNotExist()
        assertEquals(0, removals)
        compose.onNodeWithTag("vaultManagement.submit").performClick()
        compose.onNodeWithTag("delete.removeConfirm").performClick()
        assertEquals(1, removals)
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

    /** iOS `.statusToast("Password Changed", …)` after `ChangePasswordSheet(onChanged:)` (wallets-pairing.md §5). */
    @Test
    fun aChangedPasswordShowsPasswordChangedOnce() {
        var due by mutableStateOf(true)
        var shown = 0
        val viewModel = WalletDetailViewModel(WalletFixtures.store(), WalletFixtures.args())
        compose.mainClock.autoAdvance = false
        content {
            WalletDetailScreen(
                actions = WalletDetailActions(),
                onClose = {},
                passwordChanged = due,
                onPasswordChangedShown = {
                    due = false
                    shown++
                },
                viewModel = viewModel,
            )
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Password Changed").assertExists()
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(1, shown)
        compose.onNodeWithText("Password Changed").assertDoesNotExist()
    }

    /**
     * Tapping the Wallets tab while it is shown (iOS: the tab bar scrolls the stack's root to the top) brings the first
     * card back and expands the large "Wallets" title, which a programmatic scroll alone would leave collapsed.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun reselectingTheTabScrollsToTheFirstCardAndExpandsTheTitle() {
        val reselections = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        lateinit var bar: TopAppBarState
        lateinit var list: LazyListState
        val many = (1..12).map { identities.first().copy(id = WalletId(UUID(0L, it.toLong())), name = "Wallet " + it) }
        content {
            bar = rememberTopAppBarState()
            list = rememberLazyListState()
            WalletsContent(WalletsUiState(many, loaded = true), WalletsActions(), listState = list, topBarState = bar, scrollToTop = reselections)
        }
        repeat(4) { compose.onNodeWithTag("wallets.list").performTouchInput { swipeUp() } }
        compose.runOnIdle {
            assertTrue(list.firstVisibleItemIndex > 0, "scrolled down")
            assertTrue(bar.collapsedFraction > 0.99f, "the large title collapsed")
        }
        reselections.tryEmit(Unit)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
            assertEquals(0f, bar.heightOffset, "the large title is expanded again")
        }
    }

    /**
     * "Rename Wallet" (`WalletDetailView.swift:117-125`): the field is pre-filled and TalkBack reads the name being
     * edited, with "Wallet name" as its label — never a content description in place of the text. Save follows the
     * trimmed text.
     */
    @Test
    fun theRenameFieldReadsTheNameItEditsAndSaveNeedsAName() {
        val saved = mutableListOf<String>()
        content { RenameWalletDialog(currentName = "Public fixture 1", onSave = { saved += it }, onCancel = {}) }
        compose.onNodeWithTag("rename.name")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("Public fixture 1")))
            .assert(hasText("Wallet name"))
        compose.onNodeWithTag("rename.name").performTextClearance()
        compose.onNodeWithTag("rename.name").performTextInput("   ")
        compose.onNodeWithTag("rename.save").assertIsNotEnabled()
        compose.onNodeWithTag("rename.name").performTextInput("Travel Signer")
        compose.onNodeWithTag("rename.save").assertIsEnabled().performClick()
        assertEquals(listOf("   Travel Signer"), saved, "the store trims and validates the name")
    }

    /**
     * A refused password on a security page (iOS danger `Callout`, verbatim vault text) is announced to TalkBack, whose
     * focus is still on the submit button, and played with the reject haptic (ARCHITECTURE §4.5: wrong password).
     */
    @Test
    fun aRefusedPasswordIsAnnouncedAndFelt() {
        lateinit var view: View
        var state by mutableStateOf(SecurityPageUiState(identity = identities.first()))
        content {
            view = LocalView.current
            RepairWalletContent(
                uiState = state,
                fields = remember { PasswordFields(1) },
                toast = rememberStatusToastState(),
                onSubmit = {},
                onCancel = {},
            )
        }
        compose.runOnIdle { assertTrue(Shadows.shadowOf(view).lastHapticFeedbackPerformed() != HapticFeedbackConstants.REJECT, "nothing before a failure") }
        state = state.copy(error = UiText.Ios("The password is incorrect."))
        compose.onNodeWithText("The password is incorrect.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle { assertEquals(HapticFeedbackConstants.REJECT, Shadows.shadowOf(view).lastHapticFeedbackPerformed()) }
    }
}
