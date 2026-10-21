package com.bittool.signer.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.common.testing.FakeWallClock
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.DeviceStore.Companion.PASSWORD
import com.bittool.signer.core.data.doubles.PublicFixtures
import com.bittool.signer.core.data.doubles.asSecret
import com.bittool.signer.core.engine.EngineCalls
import com.bittool.signer.core.engine.EngineOperation
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletPublicMetadata
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the store does on the production crypto that the Wave-1 doubles could not show (the English-only BIP39 double,
 * the in-memory vault without attempts file, the software journal key): wallets in other wordlist languages, the
 * persistent attempt throttling, and lost Keystore keys. Behavioural equivalents of the JVM suite's double-only cases
 * (`AesGcmJournalCipher.loseKey` / `loseKeyForGood`, `LostKeyRecoveryStoreTest`). Published BIP39 vectors and synthetic
 * offline requests only.
 */
@RunWith(AndroidJUnit4::class)
class SignerStoreRealCryptoTest {
    private fun request(nonce: Int = 0) = PublicFixtures.evmRequest(nonce)

    private suspend fun DeviceStore.signOnce(nonce: Int = 0) {
        store.prepare(request(nonce))
        sign(assertNotNull(state.pendingTasks.singleOrNull(), "${state.matchingIssue}").id)
    }

    /**
     * BIP39LanguageTests vectors (`@scure/bip39` and the official bip32JP Japanese vectors) entered through
     * `SignerStore.createWallet`: the asset wordlists canonicalise the phrase (ideographic spaces, NFKD input), the vault
     * keeps the canonical words and the passphrase as typed, and the native seed equals the published seed — the
     * wallet's identity is the one the published seed derives, and the engine accepts the decrypted secret for signing.
     */
    @Test
    fun walletsInOtherWordlistLanguagesHaveThePublishedSeedsIdentityAndSign() = DeviceStore.run {
        store.reload()
        val calls = EngineCalls(DeviceEngine.real)
        for (vector in LANGUAGE_VECTORS) {
            val name = vector.language.name
            val wallet = createWallet(name, passphrase = vector.passphrase, phrase = vector.mnemonic)
            val expected = WalletPublicMetadata.parse(calls.derivePublicIndex(vector.seed.toCharArray()))
            val index = assertNotNull(state.publicMetadata(wallet.id), name)
            assertEquals(expected.walletKeyId, index.walletKeyId, name)
            assertEquals(expected.masterFingerprint, index.masterFingerprint, name)
            val canonical = DeviceCrypto.bip39.validatedCanonical(vector.mnemonic)
            assertEquals(vector.language, canonical.language)
            PASSWORD.asSecret { store.reveal(wallet.id, it) }.use { secret ->
                // Compared without printing: the vault holds the list's own spellings joined by single spaces.
                assertTrue(secret.mnemonic.chars().contentEquals(canonical.phrase.toCharArray()), "$name: the canonical words are stored")
                assertTrue(secret.passphrase.chars().contentEquals(vector.passphrase.toCharArray()), "$name: the passphrase is stored as typed")
            }
            val signer = JsonObject(
                request() + ("address" to JsonPrimitive(assertNotNull(expected.evmAddress))) +
                    ("masterFingerprint" to JsonPrimitive(expected.masterFingerprint)),
            )
            store.prepare(signer)
            val task = assertNotNull(state.pendingTasks.singleOrNull(), "$name: ${state.matchingIssue}")
            assertEquals(wallet.id, task.walletId)
            sign(task.id)
            assertTrue(state.hasSignedResponse, name)
            store.clearRequest()
        }
        assertEquals(LANGUAGE_VECTORS.size, state.wallets.size)
        assertEquals(LANGUAGE_VECTORS.size, state.receipts.size)
        // The same Japanese words with ASCII spaces are the same wallet, so the import is refused as a duplicate.
        val japanese = LANGUAGE_VECTORS.first { it.language == Bip39Language.Japanese }
        val duplicate = assertFailsWith<AppIssue> {
            createWallet("Again", passphrase = japanese.passphrase, phrase = japanese.mnemonic.replace('　', ' '))
        }
        assertEquals(UiText.Ios("This recovery phrase and passphrase already belong to an existing wallet."), duplicate.text)
    }

    /**
     * vault-crypto.md §8 through the store: from the fifth consecutive wrong password the next attempt waits (2 s
     * here), the delay is persisted in the sealed attempts file so a relaunch cannot skip it, a locked attempt is not
     * counted and never reaches the engine, and a correct password after the delay clears the counter.
     */
    @Test
    fun repeatedWrongPasswordsThrottleSigningAcrossARelaunch() {
        val clock = FakeWallClock()
        DeviceStore.run(clock = clock) {
            store.reload()
            val wallet = createWallet("Throttled fixture")
            store.prepare(request())
            val task = assertNotNull(state.pendingTasks.singleOrNull())
            repeat(5) { assertFailsWith<VaultError.AuthenticationFailed> { sign(task.id, password = "Wrong-Password$it") } }
            val locked = assertFailsWith<VaultError.TemporarilyLocked> { sign(task.id) }
            assertEquals(2L, locked.seconds)
            assertEquals(0, engine.invocationCount(EngineOperation.SignPlanTask))
            assertFalse(state.hasSignedResponse || state.busy)
            assertEquals(task.id, state.pendingTasks.single().id, "The review and plan stay for the next attempt.")
            val counter = assertNotNull(fixture.attemptsBytes(wallet.id))

            val relaunched = fixture.relaunch()
            relaunched.reload()
            relaunched.prepare(request())
            val again = assertNotNull(relaunched.state.value.pendingTasks.singleOrNull())
            assertFailsWith<VaultError.TemporarilyLocked> { PASSWORD.asSecret { relaunched.sign(again.id, it) } }
            assertContentEquals(counter, fixture.attemptsBytes(wallet.id), "A locked attempt is not counted.")
            clock.advanceBy(2_000)
            PASSWORD.asSecret { relaunched.sign(again.id, it) }
            assertTrue(relaunched.state.value.hasSignedResponse)
            // The success cleared the counter: one wrong password is an ordinary failure again.
            assertFailsWith<VaultError.AuthenticationFailed> { "Wrong-Password9".asSecret { relaunched.reveal(wallet.id, it) } }
        }
    }

    /**
     * ARCHITECTURE §6 item 19, QA L1, with the real `KeystoreJournalCipher` and `KeystoreLossProof`: the Keystore lost the
     * journal key for good (removing the screen lock deletes it; here the entry is deleted), so nothing can ever open the
     * journal again. The next launch proves the loss against probe keys and starts a fresh journal **once**: no "Unable to
     * Continue" alert, no unreadable state, the old file removed and the one-time notice recorded. The next signature is
     * saved under a new key, the notice waits until "OK" (also across a relaunch), and after it the journal is never
     * started again: its records read back under the new key.
     */
    @Test
    fun aProvablyLostKeystoreJournalKeyStartsAFreshJournalOnceWithTheNotice() = DeviceStore.run {
        store.reload()
        createWallet("Journal key fixture")
        signOnce()
        assertEquals(1, state.receipts.size)
        DeviceCrypto.deleteJournalKey()

        val relaunched = fixture.relaunch()
        relaunched.reload()
        relaunched.state.value.let {
            assertNull(it.error, "no \"could not be read\" alert: the journal could never be read again")
            assertFalse(it.activityUnreadable)
            assertTrue(it.receipts.isEmpty(), "the records the lost key sealed are gone")
            assertTrue(it.activityRestarted, "the person is told once, in Activity")
        }
        assertFalse(fixture.journalFile.exists(), "the journal nothing can open is removed")
        assertTrue(fixture.receiptRepository.restartNoticeFile.exists())

        relaunched.prepare(request(nonce = 1))
        PASSWORD.asSecret { relaunched.sign(relaunched.state.value.pendingTasks.single().id, it) }
        relaunched.state.value.let {
            assertTrue(it.hasSignedResponse)
            assertNull(it.error, "not \"could not be saved\": the new journal is sealed under a new key")
            assertEquals(1, it.receipts.size)
            assertTrue(it.activityRestarted, "the notice waits until it was read")
        }
        val saved = relaunched.state.value.receipts

        val again = fixture.relaunch()
        again.reload()
        again.state.value.let {
            assertEquals(saved, it.receipts, "the new journal reads back under its new key")
            assertTrue(it.activityRestarted, "a relaunch keeps the notice until \"OK\"")
            assertNull(it.error)
        }
        again.acknowledgeActivityRestart()
        assertFalse(again.state.value.activityRestarted)
        assertFalse(fixture.receiptRepository.restartNoticeFile.exists())

        val third = fixture.relaunch()
        third.reload()
        third.state.value.let {
            assertFalse(it.activityRestarted, "read once: never again")
            assertEquals(saved, it.receipts, "a journal whose key works is never started again")
            assertFalse(it.activityUnreadable)
        }
    }

    /**
     * QA L1 inside one process: the key this process sealed with disappears while it runs. The next signature proves the
     * loss (the cipher used the key, so only `KeystoreLossProof` lets it create another), starts the journal again with
     * the notice and saves the new record instead of "could not be saved".
     */
    @Test
    fun aJournalKeyLostWhileTheAppRunsIsReplacedAtTheNextSignature() = DeviceStore.run {
        store.reload()
        createWallet("Journal key fixture")
        signOnce()
        val sealed = fixture.journalFile.readBytes()
        DeviceCrypto.deleteJournalKey()

        signOnce(nonce = 1)
        assertTrue(state.hasSignedResponse, "A completed signature remains available.")
        assertNull(state.error, "not \"could not be saved\"")
        assertEquals(1, state.receipts.size, "the new journal holds the new signature only")
        assertTrue(state.activityRestarted)
        assertFalse(state.activityUnreadable)
        assertFalse(sealed.contentEquals(fixture.journalFile.readBytes()), "the journal was started again")

        val relaunched = fixture.relaunch()
        relaunched.reload()
        assertEquals(state.receipts, relaunched.state.value.receipts, "sealed under the new key")
        assertTrue(relaunched.state.value.activityRestarted)
    }

    /**
     * DECISIONS.md §9.3 and §10, the iOS behaviour, for a journal that does not open while nothing proves its key gone:
     * the Keystore holds a working key under the journal's alias, just not the one that sealed the file. The journal is
     * unreadable ("could not be read", "Activity Unavailable"), never started again and never overwritten: a signature
     * is still published with "could not be saved", a relaunch is unreadable again, and only the explicit "Clear
     * Activity" starts a new journal.
     */
    @Test
    fun aJournalWhoseKeyIsNotProvablyLostStaysUnreadableUntilClearActivity() = DeviceStore.run {
        store.reload()
        createWallet("Journal key fixture")
        signOnce()
        assertEquals(1, state.receipts.size)
        val sealed = fixture.journalFile.readBytes()
        DeviceCrypto.deleteJournalKey()
        // A new key under the same alias (a new process's first seal): Keystore answers with a usable key.
        DeviceCrypto.journalCipher().seal(ByteArray(0))

        val relaunched = fixture.relaunch()
        relaunched.reload()
        relaunched.state.value.let {
            assertTrue(it.activityUnreadable)
            assertFalse(it.activityRestarted, "a working key is not a lost one")
            assertTrue(it.receipts.isEmpty())
            assertEquals(UiText.Resource(R.string.data_activity_unreadable), it.error)
        }
        assertContentEquals(sealed, fixture.journalFile.readBytes(), "An unreadable journal is never overwritten.")
        assertFalse(fixture.receiptRepository.restartNoticeFile.exists())

        relaunched.setError(null)
        relaunched.prepare(request(nonce = 1))
        PASSWORD.asSecret { relaunched.sign(relaunched.state.value.pendingTasks.single().id, it) }
        assertTrue(relaunched.state.value.hasSignedResponse, "A completed signature remains available.")
        assertEquals(UiText.Resource(R.string.data_activity_not_saved), relaunched.state.value.error)
        assertContentEquals(sealed, fixture.journalFile.readBytes())

        val again = fixture.relaunch()
        again.reload()
        assertTrue(again.state.value.activityUnreadable)
        assertFalse(again.state.value.activityRestarted)
        assertContentEquals(sealed, fixture.journalFile.readBytes())

        again.clearHistory()
        assertFalse(again.state.value.activityUnreadable)
        again.prepare(request(nonce = 2))
        PASSWORD.asSecret { again.sign(again.state.value.pendingTasks.single().id, it) }
        assertEquals(1, again.state.value.receipts.size)
        val next = fixture.relaunch()
        next.reload()
        assertEquals(again.state.value.receipts, next.state.value.receipts, "The new journal reads back under the key.")
        assertFalse(next.state.value.activityUnreadable)
        assertFalse(next.state.value.activityRestarted)
    }

    /**
     * A wallet whose Android Keystore device key is gone (vault-crypto.md §17.3: a missing key is never recreated)
     * cannot be unlocked: signing fails with "This wallet’s device key is unavailable…" before the engine is called,
     * and the review, plan and public side are untouched.
     */
    @Test
    fun aLostKeystoreWalletKeyRefusesSigningBeforeTheEngine() = DeviceStore.run(deviceKeys = DeviceCrypto.keystoreDeviceKeys()) {
        store.reload()
        val wallet = createWallet("Device key fixture")
        assertTrue(DeviceCrypto.hasKeystoreKey(wallet.id))
        store.prepare(request())
        val task = assertNotNull(state.pendingTasks.singleOrNull())
        DeviceCrypto.deleteKeystoreKeys(listOf(wallet.id))
        assertFailsWith<VaultError.DeviceKeyUnavailable> { sign(task.id) }
        assertEquals(0, engine.invocationCount(EngineOperation.SignPlanTask))
        assertFalse(state.hasSignedResponse || state.busy)
        assertTrue(state.receipts.isEmpty())
        assertEquals(task.id, state.pendingTasks.single().id)
        store.reload()
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(wallet.id), "The sidecar's HMAC key is separate.")
        assertFalse(DeviceCrypto.hasKeystoreKey(wallet.id), "A missing key is never silently recreated.")
    }

    /**
     * QA backlog B9 on the production stack: a wallet whose Keystore key is gone is a dead end (no deletion with the
     * password, no reset from the phrase, no re-import: its sidecar keeps the walletKeyId) until
     * `removeWalletWithLostDeviceKey` removes it without a password — its vault file, attempts file and sidecar go, the
     * other wallet is untouched, and the same phrase imports again under a new id and a new Keystore key.
     */
    @Test
    fun aWalletWhoseKeystoreKeyIsGoneIsRemovedWithoutItsPasswordAndImportsAgain() =
        DeviceStore.run(deviceKeys = DeviceCrypto.keystoreDeviceKeys()) {
            store.reload()
            val kept = createWallet("Kept", passphrase = PublicFixtures.ALTERNATE_PASSPHRASE)
            val lost = createWallet("Lost")
            assertFalse(store.isDeviceKeyLost(lost.id))
            assertFailsWith<VaultError.DeviceKeyLossUnconfirmed> { store.removeWalletWithLostDeviceKey(lost.id) }
            assertEquals(listOf(kept.id, lost.id), state.wallets.map { it.id })

            DeviceCrypto.deleteKeystoreKeys(listOf(lost.id))
            assertFailsWith<VaultError.DeviceKeyUnavailable> { PASSWORD.asSecret { store.deleteWallet(lost.id, it) } }
            assertFailsWith<VaultError.DeviceKeyUnavailable> {
                PublicFixtures.PHRASE.asSecret { words ->
                    "".asSecret { pass -> "Replacement-Password2".asSecret { pw -> store.resetPassword(lost.id, words, pass, pw) } }
                }
            }
            val duplicate = assertFailsWith<AppIssue> { createWallet("Again") }
            assertEquals(UiText.Ios("This recovery phrase and passphrase already belong to an existing wallet."), duplicate.text)

            assertTrue(store.isDeviceKeyLost(lost.id))
            store.removeWalletWithLostDeviceKey(lost.id)
            assertEquals(listOf(kept.id), state.wallets.map { it.id })
            assertFalse(fixture.vaultFile(lost.id).exists())
            assertFalse(fixture.attemptsFile(lost.id).exists())
            assertFalse(sidecar(lost.id).exists())
            assertEquals(1, state.navigationResetEpoch)
            assertTrue(DeviceCrypto.hasKeystoreKey(kept.id), "the other wallet keeps its key")
            PASSWORD.asSecret { store.reveal(kept.id, it) }.close()

            val restored = createWallet("Restored")
            assertEquals(listOf(kept.id, restored.id), state.wallets.map { it.id })
            assertTrue(DeviceCrypto.hasKeystoreKey(restored.id))
            assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(restored.id))
        }

    /**
     * QA backlog B9 when Keystore lost every key at once — the wallet's and the public-data key, as removing the screen
     * lock does to keys that need an unlocked device: the public file fails its MAC, so the wallet needs Repair, which
     * needs the lost key. It is removed without its password, and the same phrase imports again **in the same
     * process**, although this process had used the public-data key before: Keystore provably lacks it, so it is
     * created again for the new wallet's public file.
     */
    @Test
    fun aKeystoreThatLostEveryKeyLetsTheWalletBeRemovedAndImportedAgainWithoutARelaunch() =
        DeviceStore.run(deviceKeys = DeviceCrypto.keystoreDeviceKeys()) {
            store.reload()
            val lost = createWallet("Lost")
            assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(lost.id))

            DeviceCrypto.deleteKeystoreKeys(listOf(lost.id))
            DeviceCrypto.deletePublicDataKey()
            store.reload()
            assertEquals(PublicMetadataStatus.Invalid, state.metadataStatus(lost.id))
            assertFailsWith<VaultError.DeviceKeyUnavailable> { PASSWORD.asSecret { store.completeSetup(lost.id, it) } }

            assertTrue(store.isDeviceKeyLost(lost.id))
            store.removeWalletWithLostDeviceKey(lost.id)
            assertTrue(state.wallets.isEmpty())
            assertFalse(sidecar(lost.id).exists())

            val restored = createWallet("Restored")
            assertEquals(listOf(restored.id), state.wallets.map { it.id })
            assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(restored.id))
            PASSWORD.asSecret { store.reveal(restored.id, it) }.close()
        }

    private class LanguageVector(val language: Bip39Language, val mnemonic: String, val passphrase: String, val seed: String)

    private companion object {
        /** From `BitToolSignerTests/BIP39LanguageVectors.json` (NFKD as published; Japanese with U+3000 separators). */
        val LANGUAGE_VECTORS = listOf(
            LanguageVector(
                Bip39Language.Japanese,
                "あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あおぞら",
                "㍍ガバヴァぱばぐゞちぢ十人十色",
                "a262d6fb6122ecf45be09c50492b31f92e9beb7d9a845987a02cefda57a15f9c467a17872029a9e92299b5cbdf306e3a0ee620245cbd508959b6cb7ca637bd55",
            ),
            LanguageVector(
                Bip39Language.ChineseSimplified,
                "柄 需 固 姆 色 斥 霍 握 宾 琴 况 团 抵 经 摸 郭 沙 鸣 拖 妙 阳 辈 掉 迁",
                "",
                "881a595911493adff29a4bee5d40aab7d82e6714494ee3a4633da4976841f9b9ef20c6e43e4977b3d2d3fde59cf5437983df78d0321daee289fe6847a0324b79",
            ),
            LanguageVector(
                Bip39Language.Spanish,
                "obra diadema gorila farmacia colgar gorra pausa talar cocina duda dragón optar",
                "Ünïcødé passphrase 你好",
                "b9bf02563d7b2ee8348bed5fc4b446a3e369c2c123c4cd943dfc85f85adb0a15aea0e78110ab7e297912924403429bd3e53b4c56010c9708ef0391a012cd5faf",
            ),
            LanguageVector(
                Bip39Language.Korean,
                "실장 활동 큰절 흔적 형제 제대로 훈련 한글 실장 활동 큰절 흔히",
                "TREZOR",
                "e6995bf885f5c64932ca28bbb00bc100a6b89cb6edc987bb05f05f99ae7caf78329029c189834c1cca938000bcf08423da011558a60cf3d90c9035eaaf241b9e",
            ),
        )
    }
}
