package com.bittool.signer.core.data

import android.app.KeyguardManager
import com.bittool.signer.core.crypto.Bip39Seed
import com.bittool.signer.core.crypto.Pbkdf2Bip39Seed
import com.bittool.signer.core.crypto.bip39.Bip39
import com.bittool.signer.core.crypto.bip39.Bip39WordlistSource
import com.bittool.signer.core.crypto.bip39.DefaultBip39
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore
import com.bittool.signer.core.crypto.vault.DeviceKeyStore
import com.bittool.signer.core.crypto.vault.JournalCipher
import com.bittool.signer.core.crypto.vault.KeystoreJournalCipher
import com.bittool.signer.core.crypto.vault.SignerFiles
import com.bittool.signer.core.model.QrCodeEncoder
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.scanner.ZxingQrCodeEncoder
import java.lang.reflect.Proxy
import java.security.KeyStore

/**
 * The production `:core:crypto` and `:core:scanner` implementations the store runs on in the app (`CryptoModule`,
 * `ScannerModule`), constructed directly for the instrumented store tests: the asset-backed `DefaultBip39` (ten
 * SHA-256-pinned wordlists), the native `Pbkdf2Bip39Seed`, the ZXing `QrCodeEncoder` and the Android Keystore journal
 * cipher and device-key store. Each test builds its `DefaultWalletVault` and `SystemSecureRandomSource` itself
 * ([DeviceStore], [RealStoreFixture]).
 *
 * **Keystore on a device without a lock screen.** The production Keystore configuration (`KeystoreKeyPolicy.forDevice`)
 * marks every key `setUnlockedDeviceRequired(true)`, which API 31 Keystore refuses to generate while no secure lock
 * screen exists, and `AndroidKeystoreDeviceKeyStore` refuses to create keys without one (vault-crypto.md §17.3). The
 * shared test emulator has no lock screen and the tests must not change the device's security settings, so on such a
 * device [journalCipher] and [keystoreDeviceKeys] construct the same classes with the policy `:core:crypto`'s own
 * Keystore tests use (`KeystoreKeyPolicy(strongBox = false, unlockedDeviceRequired = false)`, a lock-screen gate that
 * answers "secure"). Those constructors are `internal` to `:core:crypto`, so they are reached reflectively; every
 * cryptographic operation is still the production code under real non-exportable Keystore keys. On a device with a
 * lock screen the production `@Inject` constructors are used unchanged.
 */
object DeviceCrypto {
    /** The asset wordlists are read once per test process (as the `@Singleton` binding does). */
    val bip39: Bip39 by lazy { DefaultBip39(Bip39WordlistSource.assets(DeviceEngine.context.assets)) }

    val seed: Bip39Seed = Pbkdf2Bip39Seed()

    val encoder: QrCodeEncoder = ZxingQrCodeEncoder()

    /** `KeyguardManager.isDeviceSecure()`, the production lock-screen gate. */
    val isDeviceSecure: Boolean
        get() = DeviceEngine.context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** A new `KeystoreJournalCipher` (one per simulated process): the alias `com.bittool.signer.activity.v1` persists. */
    fun journalCipher(): JournalCipher =
        if (isDeviceSecure) {
            KeystoreJournalCipher()
        } else {
            KeystoreJournalCipher::class.java.getDeclaredConstructor(policyClass).newInstance(testPolicy())
        }

    /** A new `AndroidKeystoreDeviceKeyStore`: per-wallet AES keys and the HMAC public-data key in the Android Keystore. */
    fun keystoreDeviceKeys(): DeviceKeyStore =
        if (isDeviceSecure) {
            AndroidKeystoreDeviceKeyStore(DeviceEngine.context)
        } else {
            AndroidKeystoreDeviceKeyStore::class.java.getDeclaredConstructor(lockScreenClass, policyClass)
                .newInstance(secureLockScreen(), testPolicy())
        }

    /** Whether the Keystore holds a device key for [id] (the vault creates it with the wallet and deletes it with it). */
    fun hasKeystoreKey(id: WalletId): Boolean = keystore().containsAlias(AndroidKeystoreDeviceKeyStore.ALIAS_PREFIX + id)

    /** Removes the Keystore device keys of [ids] a failed test may leave behind (never the public-data key). */
    fun deleteKeystoreKeys(ids: Collection<WalletId>) {
        val keystore = keystore()
        for (id in ids) {
            val alias = AndroidKeystoreDeviceKeyStore.ALIAS_PREFIX + id
            if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
        }
    }

    /**
     * Deletes the Activity journal key `com.bittool.signer.activity.v1`, as if Keystore had lost it. A cipher that already
     * used it then refuses to seal; a new process (a new `KeystoreJournalCipher`) creates a new key on its first seal.
     */
    fun deleteJournalKey() {
        val keystore = keystore()
        if (keystore.containsAlias(SignerFiles.RECEIPTS_KEY_ALIAS)) keystore.deleteEntry(SignerFiles.RECEIPTS_KEY_ALIAS)
    }

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private val policyClass: Class<*> by lazy { Class.forName("com.bittool.signer.core.crypto.vault.KeystoreKeyPolicy") }

    private val lockScreenClass: Class<*> by lazy { Class.forName("com.bittool.signer.core.crypto.vault.DeviceLockScreen") }

    /** `KeystoreKeyPolicy(strongBox = false, unlockedDeviceRequired = false)`. */
    private fun testPolicy(): Any =
        policyClass.getDeclaredConstructor(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).newInstance(false, false)

    /** A `DeviceLockScreen` that reports a secure lock screen. */
    private fun secureLockScreen(): Any = Proxy.newProxyInstance(lockScreenClass.classLoader, arrayOf(lockScreenClass)) { proxy, method, args ->
        when (method.name) {
            "isSecure" -> true
            "toString" -> "DeviceLockScreen(test: secure)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> throw UnsupportedOperationException(method.name)
        }
    }
}
