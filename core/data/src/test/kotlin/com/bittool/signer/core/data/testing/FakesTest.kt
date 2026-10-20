package com.bittool.signer.core.data.testing

import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.PreferenceValues
import com.bittool.signer.core.model.Appearance
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.LegalDocumentParser
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.registry.ReferenceData
import com.bittool.signer.core.model.signing.MatchingIssue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The published Wave-2 fakes behave as their KDoc says. */
class FakesTest {
    @Test
    fun theFakeStoreRecordsCallsAndBehavesLikeASmallStore() = runTest {
        val store = FakeSignerStore()
        val created = SecretText.of("p").use { p -> SecretText.of("e").use { e -> SecretText.of("Secret-1").use { pw -> store.createWallet("  Main ", p, e, pw) } } }
        assertEquals("Main", created.name)
        assertEquals(listOf(created), store.state.value.wallets)
        assertEquals(FakeSignerStore.Call.CreateWallet("  Main ", "p", "e", "Secret-1"), store.calls.single())
        store.renameWallet(created.id, " Renamed ")
        assertEquals("Renamed", store.state.value.wallets.single().name)
        SecretText.of("Secret-1").use { store.deleteWallet(created.id, it) }
        assertTrue(store.state.value.wallets.isEmpty())
        assertEquals(1, store.state.value.navigationResetEpoch)
        assertEquals(1, store.state.value.securityEpoch)
        assertEquals(12, store.generateMnemonic(12).use { String(it.chars()).split(' ').size })
        assertEquals(24, store.generateMnemonic(24).use { String(it.chars()).split(' ').size })
        assertFailsWith<FakeSignerStore.NotScripted> { store.walletPairing(WalletId.random()) }
        assertEquals(2, store.calls<FakeSignerStore.Call.GenerateMnemonic>().size)
    }

    @Test
    fun theFakeStoreRemovesOnlyWalletsWithAProvablyLostKey() = runTest {
        val store = FakeSignerStore()
        val created = SecretText.of("p").use { p -> SecretText.of("").use { e -> SecretText.of("Secret-1").use { pw -> store.createWallet("Main", p, e, pw) } } }
        assertFalse(store.isDeviceKeyLost(created.id))
        assertFailsWith<VaultError.DeviceKeyLossUnconfirmed> { store.removeWalletWithLostDeviceKey(created.id) }
        assertEquals(listOf(created), store.state.value.wallets)
        store.lostDeviceKeys = setOf(created.id)
        assertTrue(store.isDeviceKeyLost(created.id))
        store.removeWalletWithLostDeviceKey(created.id)
        assertTrue(store.state.value.wallets.isEmpty())
        assertEquals(1, store.state.value.navigationResetEpoch)
        assertEquals(1, store.state.value.securityEpoch)
        assertEquals(
            listOf(FakeSignerStore.Call.RemoveWalletWithLostDeviceKey(created.id), FakeSignerStore.Call.RemoveWalletWithLostDeviceKey(created.id)),
            store.calls<FakeSignerStore.Call.RemoveWalletWithLostDeviceKey>(),
        )
    }

    @Test
    fun aScriptedOperationCanBeHeldAndFailed() = runTest {
        val store = FakeSignerStore()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        store.onSign = { _, _, _ -> gate.await(); error("declined") }
        val signing = async { runCatching { SecretText.of("x").use { store.sign("task", it) } } }
        runCurrent()
        assertFalse(signing.isCompleted)
        gate.complete(Unit)
        assertEquals("declined", signing.await().exceptionOrNull()?.message)
        assertEquals(FakeSignerStore.Call.Sign("task", "x", false), store.calls.single())
    }

    @Test
    fun suspendingTheFakeFollowsTheStoreRules() {
        val store = FakeSignerStore()
        store.setState { it.copy(busy = true) }
        store.suspendSession()
        assertFalse(store.state.value.busy)
        assertNull(store.state.value.matchingIssue)
        assertEquals(1, store.state.value.securityEpoch)
        store.clearRequest()
        assertEquals(listOf(FakeSignerStore.Call.SuspendSession, FakeSignerStore.Call.ClearRequest), store.calls)
        assertTrue(MatchingIssue.Interrupted.canRetry)
    }

    @Test
    fun theFakePreferencesCanStartLoading() = runTest {
        val preferences = FakeSignerPreferences(initial = null)
        val loading = async { preferences.load() }
        runCurrent()
        assertFalse(loading.isCompleted)
        preferences.publish(PreferenceValues(appLockEnabled = true))
        assertTrue(loading.await().appLockEnabled)
        preferences.setAppearance(Appearance.Dark)
        assertEquals(Appearance.Dark, preferences.values.value?.appearance)
        preferences.failure = IllegalStateException("disk full")
        assertFailsWith<IllegalStateException> { preferences.setAppLockEnabled(false) }
        assertTrue(preferences.values.value!!.appLockEnabled)
        assertEquals(2, preferences.calls.size)
    }

    @Test
    fun theFakeRepositoriesReturnWhatTheyAreGiven() = runTest {
        val reference = FakeReferenceDataRepository()
        assertEquals(ReferenceData.Empty, reference.load())
        assertEquals("2026-09-22.1", reference.registryVersion())
        assertEquals(1, reference.loadCount)
        reference.version = null
        assertNull(reference.registryVersion())
        val legal = FakeLegalDocumentRepository()
        assertEquals(listOf(LegalBlock(0, LegalBlock.Kind.Paragraph, LegalDocumentParser.MISSING)), legal.blocks(LegalDocument.Terms))
        legal.documents = mapOf(LegalDocument.Terms to FakeLegalDocumentRepository.parsed(LegalDocument.Terms, "Terms of Use\nVersion 1\n\nBody"))
        assertEquals(listOf(LegalBlock.Kind.Version, LegalBlock.Kind.Paragraph), legal.blocks(LegalDocument.Terms).map { it.kind })
    }
}
