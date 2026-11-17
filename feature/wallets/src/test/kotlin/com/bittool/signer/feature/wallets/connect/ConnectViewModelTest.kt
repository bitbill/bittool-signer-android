package com.bittool.signer.feature.wallets.connect

import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.PreparedConnect
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.data.testing.FakeSignerStore.Call
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PairingLevel
import com.bittool.signer.core.model.QrMatrix
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.navigation.ConnectRoute
import com.bittool.signer.feature.wallets.MainDispatcherRule
import com.bittool.signer.feature.wallets.WalletFixtures
import com.bittool.signer.feature.wallets.observe
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Connect's loading state machine and options (wallets-pairing.md §7.1, §7.6; iOS `ConnectView.swift:116-347`). */
class ConnectViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun scriptedStore(): FakeSignerStore = WalletFixtures.store().apply {
        onPairing = { _, chain, profile, mode, index ->
            WalletFixtures.coinPayload(path = "${chain.wire}/$profile/$mode/$index", mode = mode)
        }
        onWalletPairing = { WalletFixtures.walletPayload() }
    }

    private fun coin(store: FakeSignerStore, chain: Chain = Chain.Bitcoin) =
        ConnectViewModel(store, WalletFixtures.args(level = ConnectRoute.LEVEL_COIN, chain = chain.wire))

    private fun wallet(store: FakeSignerStore) = ConnectViewModel(store, WalletFixtures.args(level = ConnectRoute.LEVEL_WALLET))

    @Test
    fun aChainRowOpensCoinLevelForThatChainWithTheIosDefaults() = runTest(main.dispatcher) {
        val store = scriptedStore()
        val state = observe(coin(store).uiState)
        advanceUntilIdle()
        assertEquals(PairingLevel.Coin, state.value.level)
        assertEquals(
            listOf(Call.Pairing(WalletFixtures.readyId, Chain.Bitcoin, "native", "accountXpub", 0)),
            store.calls<Call.Pairing>(),
        )
        assertEquals("bitcoin|native|accountXpub|0", state.value.options.requestKey)
        assertNotNull(state.value.coin)
        assertEquals(store.calls<Call.Pairing>().size, 1)
        assertTrue(store.calls<Call.WalletPairing>().isEmpty(), "Wallet Level loads only when chosen")
    }

    @Test
    fun everyOptionIsANewRequestWithTheExactEngineParameters() = runTest(main.dispatcher) {
        val store = scriptedStore()
        val vm = coin(store)
        observe(vm.uiState)
        advanceUntilIdle()
        vm.selectOutputMode("address")
        advanceUntilIdle()
        vm.selectMultisig(true)
        advanceUntilIdle()
        vm.selectBitcoinProfile("multisig-nested")
        advanceUntilIdle()
        vm.selectChain(Chain.Evm)
        advanceUntilIdle()
        vm.selectOutputMode("address")
        advanceUntilIdle()
        vm.selectChain(Chain.Solana)
        advanceUntilIdle()
        vm.selectSolanaProfile("trust")
        advanceUntilIdle()
        vm.selectChain(Chain.Tron)
        advanceUntilIdle()
        vm.selectTronIndex(123_456)
        advanceUntilIdle()
        val id = WalletFixtures.readyId
        assertEquals(
            listOf(
                Call.Pairing(id, Chain.Bitcoin, "native", "accountXpub", 0),
                Call.Pairing(id, Chain.Bitcoin, "native", "address", 0),
                // Multisig always exports the cosigner account xpub, whatever "Export as" said for single-sig.
                Call.Pairing(id, Chain.Bitcoin, "multisig-native", "accountXpub", 0),
                Call.Pairing(id, Chain.Bitcoin, "multisig-nested", "accountXpub", 0),
                Call.Pairing(id, Chain.Evm, "standard", "wallet", 0),
                Call.Pairing(id, Chain.Evm, "standard", "address", 0),
                Call.Pairing(id, Chain.Solana, "standard", "wallet", 0),
                Call.Pairing(id, Chain.Solana, "trust", "wallet", 0),
                // TRON always exports an address, at the chosen index.
                Call.Pairing(id, Chain.Tron, "standard", "address", 0),
                Call.Pairing(id, Chain.Tron, "standard", "address", 123_456),
            ),
            store.calls<Call.Pairing>(),
        )
    }

    @Test
    fun everyChainKeepsItsOwnOptionsForTheLifeOfThePage() = runTest(main.dispatcher) {
        val store = scriptedStore()
        val vm = coin(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        vm.selectOutputMode("address")
        vm.selectChain(Chain.Evm)
        vm.selectChain(Chain.Bitcoin)
        advanceUntilIdle()
        assertEquals("address", state.value.options.outputMode)
        vm.selectLevel(PairingLevel.Wallet)
        vm.selectLevel(PairingLevel.Coin)
        advanceUntilIdle()
        assertEquals("bitcoin|native|address|0", state.value.options.requestKey, "switching levels never resets the options")
    }

    @Test
    fun aStaleResultIsNeverShown() = runTest(main.dispatcher) {
        val first = CompletableDeferred<Unit>()
        val store = scriptedStore().apply {
            val scripted = onPairing
            onPairing = { id, chain, profile, mode, index ->
                if (mode == "accountXpub" && chain == Chain.Bitcoin) first.await()
                scripted(id, chain, profile, mode, index)
            }
        }
        val vm = coin(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        assertNull(state.value.coin, "placeholder while loading")
        vm.selectOutputMode("address")
        advanceUntilIdle()
        first.complete(Unit)
        advanceUntilIdle()
        assertEquals("address", state.value.coin?.outputMode, "the cancelled account export never replaces the address export")
    }

    @Test
    fun theFirstCoinResultArrivesWithoutAnimationAndLaterOnesFade() = runTest(main.dispatcher) {
        val store = scriptedStore()
        val vm = coin(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        assertFalse(state.value.animateCoin, "the first result may land during the push transition")
        vm.selectOutputMode("address")
        advanceUntilIdle()
        assertTrue(state.value.animateCoin)
    }

    @Test
    fun returningToCoinLevelWithTheSameSelectionDoesNotReload() = runTest(main.dispatcher) {
        val store = scriptedStore()
        val vm = coin(store)
        observe(vm.uiState)
        advanceUntilIdle()
        vm.selectLevel(PairingLevel.Wallet)
        advanceUntilIdle()
        vm.selectLevel(PairingLevel.Coin)
        advanceUntilIdle()
        assertEquals(1, store.calls<Call.Pairing>().size)
        assertEquals(1, store.calls<Call.WalletPairing>().size)
    }

    @Test
    fun walletLevelOpensOnThePrefetchedCodeAndStillAsksTheEngine() = runTest(main.dispatcher) {
        val prepared = WalletFixtures.walletPayload()
        val matrices = prepared.frames.map { QrMatrix(21, BooleanArray(21 * 21)) }
        val hold = CompletableDeferred<Unit>()
        val store = scriptedStore().apply {
            preparedConnects = mapOf(WalletFixtures.readyId to PreparedConnect(prepared, matrices))
            onWalletPairing = {
                hold.await()
                WalletFixtures.walletPayload()
            }
        }
        val vm = wallet(store)
        assertEquals(prepared, vm.uiState.value.wallet, "complete on the first frame, before any collection")
        val state = observe(vm.uiState)
        assertEquals(prepared, state.value.wallet)
        assertSame(matrices, state.value.walletMatrices)
        assertEquals(prepared.text, state.value.copyText)
        advanceUntilIdle()
        assertEquals(1, store.calls<Call.WalletPairing>().size, "Connect still issues its own createWalletPairing")
        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(prepared, state.value.wallet)
        assertSame(matrices, state.value.walletMatrices, "identical frames keep the prepared matrices")
    }

    @Test
    fun theSharedErrorIsShownAndClearedWhenALevelLoadsAgain() = runTest(main.dispatcher) {
        var fail = true
        val store = scriptedStore().apply {
            onWalletPairing = {
                if (fail) throw IllegalStateException("Unsupported wallet pairing mode") else WalletFixtures.walletPayload()
            }
        }
        val vm = wallet(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        assertEquals(UiText.Verbatim("Unsupported wallet pairing mode"), state.value.error)
        assertNull(state.value.copyText, "Copy QR Data is disabled without a code")
        fail = false
        vm.selectLevel(PairingLevel.Coin)
        advanceUntilIdle()
        assertNull(state.value.error, "a level that loads clears the shared error first")
        vm.selectLevel(PairingLevel.Wallet)
        advanceUntilIdle()
        assertNotNull(state.value.wallet)
        assertNull(state.value.error)
    }

    @Test
    fun copyTextIsExactlyTheTextOfTheCodeOnScreen() = runTest(main.dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val store = scriptedStore().apply {
            onWalletPairing = {
                hold.await()
                WalletFixtures.walletPayload()
            }
        }
        val vm = coin(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        assertEquals(state.value.coin?.text, state.value.copyText)
        vm.selectLevel(PairingLevel.Wallet)
        assertNull(state.value.copyText, "nothing to copy until the wallet code exists")
        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(WalletFixtures.walletPayload().text, state.value.copyText)
    }

    @Test
    fun theTronIndexIsClampedToTheLargestNonHardenedIndex() = runTest(main.dispatcher) {
        val store = scriptedStore()
        val vm = coin(store, Chain.Tron)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        vm.selectTronIndex(9_999_999_999)
        advanceUntilIdle()
        assertEquals(2_147_483_647L, state.value.options.tronIndex)
        assertEquals("m/44'/195'/0'/0/2147483647", state.value.options.tronPath)
        assertEquals(2_147_483_647L, store.calls<Call.Pairing>().last().index)
    }

    @Test
    fun theMasterFingerprintIsShownUpperCase() = runTest(main.dispatcher) {
        val state = observe(coin(scriptedStore()).uiState)
        advanceUntilIdle()
        assertEquals("73C5DA0A", state.value.masterFingerprint)
    }

    // ---- KH v2: a wallet saved before 1.1.0 (iOS `needsIdentity`, `WalletIdentityAuthorizationView`) ---------------

    private fun unsignedStore(): FakeSignerStore = WalletFixtures.store(WalletFixtures.state(unsigned = true)).apply {
        onPairing = { _, chain, profile, mode, index -> WalletFixtures.coinPayload(path = "${chain.wire}/$profile/$mode/$index", mode = mode) }
        onWalletPairing = { WalletFixtures.walletPayload() }
        // The real store saves the signature and reloads; the state then has it.
        onAuthorizeWalletIdentity = { id, _ -> setState { it.copy(walletIdentities = it.walletIdentities + (id to WalletFixtures.identity)) } }
    }

    @Test
    fun anUnsignedWalletShowsThePasswordCardInsteadOfACode() = runTest(main.dispatcher) {
        val store = unsignedStore()
        val vm = wallet(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        assertTrue(state.value.needsIdentity)
        assertNull(state.value.wallet)
        assertNull(state.value.error, "no error next to the card: nothing was loaded")
        assertNull(state.value.copyText)
        assertTrue(store.calls<Call.WalletPairing>().isEmpty(), "no code is asked for without the identity")
        // Coin Level is unchanged and needs no password.
        vm.selectLevel(PairingLevel.Coin)
        advanceUntilIdle()
        assertNotNull(state.value.coin)
        assertTrue(store.calls<Call.AuthorizeWalletIdentity>().isEmpty())
    }

    @Test
    fun thePasswordOnceSignsAndTheCodeLoadsByItself() = runTest(main.dispatcher) {
        val store = unsignedStore()
        val vm = wallet(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        val password = SecretText.of("Public-Test-Password1!")
        vm.authorizeIdentity(password)
        advanceUntilIdle()
        assertEquals(listOf(Call.AuthorizeWalletIdentity(WalletFixtures.readyId, "Public-Test-Password1!")), store.calls<Call.AuthorizeWalletIdentity>())
        assertTrue(password.isEmpty(), "the password is wiped after use")
        assertFalse(state.value.needsIdentity)
        assertFalse(state.value.identityBusy)
        assertEquals(1, store.calls<Call.WalletPairing>().size, "the v2 code loads once the identity exists")
        assertNotNull(state.value.wallet)
        // The second time the page opens: no card, no password.
        val again = observe(wallet(store).uiState)
        advanceUntilIdle()
        assertFalse(again.value.needsIdentity)
        assertNotNull(again.value.wallet)
        assertEquals(1, store.calls<Call.AuthorizeWalletIdentity>().size)
    }

    @Test
    fun aWrongPasswordStaysOnTheCardAndProducesNoCode() = runTest(main.dispatcher) {
        val store = unsignedStore().apply { onAuthorizeWalletIdentity = { _, _ -> throw VaultError.AuthenticationFailed() } }
        val vm = wallet(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        vm.authorizeIdentity(SecretText.of("Wrong-Password1"))
        advanceUntilIdle()
        assertEquals(UiText.Ios("The password is incorrect."), state.value.identityError)
        assertTrue(state.value.needsIdentity)
        assertNull(state.value.wallet)
        assertTrue(store.calls<Call.WalletPairing>().isEmpty())
    }

    @Test
    fun leavingTheCardCancelsAndAnEmptyPasswordIsIgnored() = runTest(main.dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val store = unsignedStore().apply { onAuthorizeWalletIdentity = { _, _ -> hold.await() } }
        val vm = wallet(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        vm.authorizeIdentity(SecretText.of(""))
        advanceUntilIdle()
        assertTrue(store.calls<Call.AuthorizeWalletIdentity>().isEmpty())
        vm.authorizeIdentity(SecretText.of("Public-Test-Password1!"))
        advanceUntilIdle()
        assertTrue(state.value.identityBusy)
        vm.cancelIdentity()
        advanceUntilIdle()
        assertFalse(state.value.identityBusy)
        assertTrue(state.value.needsIdentity, "cancelling leaves the page without a code")
        assertTrue(store.calls<Call.WalletPairing>().isEmpty())
    }
}
