package com.bittool.signer.core.crypto.vault

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Whether the device has a secure lock screen (PIN, pattern, password): `KeyguardManager.isDeviceSecure()`. */
internal fun interface DeviceLockScreen {
    fun isSecure(): Boolean

    companion object {
        fun of(context: Context): DeviceLockScreen {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            return DeviceLockScreen { keyguard?.isDeviceSecure == true }
        }
    }
}

/**
 * How Keystore keys are generated (vault-crypto.md §17.3–§17.4). Production: [forDevice]. Tests on an emulator
 * without a lock screen pass [unlockedDeviceRequired] = false, because API 31 Keystore refuses to generate an
 * unlocked-device-required key while no lock screen exists (measured: "Keystore key generation failed / System
 * error"); production never reaches key generation without one ([DeviceLockScreen]).
 */
internal data class KeystoreKeyPolicy(
    /** Try StrongBox first for the per-wallet AES keys (falls back to the TEE on `StrongBoxUnavailableException`). */
    val strongBox: Boolean,
    /** `setUnlockedDeviceRequired(true)` (API 28+): keys are unusable while the device is locked. */
    val unlockedDeviceRequired: Boolean,
) {
    companion object {
        fun forDevice(context: Context) = KeystoreKeyPolicy(
            strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE),
            unlockedDeviceRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
        )
    }
}

/**
 * The Android Keystore operations shared by the device-key store and the journal cipher. Every alias is created only
 * after [contains] said it is absent, and is never regenerated over an existing entry: `KeyGenerator.generateKey()`
 * silently replaces an alias (vault-crypto.md §17.3 pitfall). Failures surface as the platform throws them
 * ([GeneralSecurityException], [ProviderException], [IOException]); callers map them to [VaultError]s.
 */
internal object Keystore {
    private const val PROVIDER = "AndroidKeyStore"

    fun open(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** The secret key under [alias], or null when the alias is absent or holds something else. */
    fun secretKey(store: KeyStore, alias: String): SecretKey? = store.getKey(alias, null) as? SecretKey

    fun contains(store: KeyStore, alias: String): Boolean = store.containsAlias(alias)

    /**
     * A new AES-256-GCM key (randomised encryption: Keystore picks every IV). With [KeystoreKeyPolicy.strongBox] it is
     * first generated in StrongBox; on `StrongBoxUnavailableException` (or StrongBox rejecting the spec) the entry of
     * that failed attempt, if any, is removed and the key is generated in the TEE.
     */
    fun generateAes(store: KeyStore, alias: String, policy: KeystoreKeyPolicy, allowStrongBox: Boolean = true): SecretKey {
        if (allowStrongBox && policy.strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(KeyProperties.KEY_ALGORITHM_AES, aesSpec(alias, policy, strongBox = true))
            } catch (_: ProviderException) {
                // StrongBoxUnavailableException (API 28) is a ProviderException; a late StrongBox failure too.
            } catch (_: GeneralSecurityException) {
                // StrongBox rejected the key spec.
            }
            if (contains(store, alias)) store.deleteEntry(alias)
        }
        return generate(KeyProperties.KEY_ALGORITHM_AES, aesSpec(alias, policy, strongBox = false))
    }

    /**
     * A throw-away AES key under [alias] that protects nothing: generated only to see Keystore answer (the lost-key proof
     * of `AndroidKeystoreDeviceKeyStore.isKeyLost`) and deleted right after. TEE, no `setUnlockedDeviceRequired`, so it
     * can be generated whatever the lock-screen state.
     */
    fun generateProbe(alias: String): SecretKey = generate(
        KeyProperties.KEY_ALGORITHM_AES,
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT)
            .setKeySize(128)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build(),
    )

    /** A new 256-bit HMAC-SHA256 key in the TEE (it MACs every public file on every reload: StrongBox is too slow). */
    fun generateHmac(alias: String, policy: KeystoreKeyPolicy): SecretKey {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(256)
        if (policy.unlockedDeviceRequired && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUnlockedDeviceRequired(true)
        return generate(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, builder.build())
    }

    private fun aesSpec(alias: String, policy: KeystoreKeyPolicy, strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        // No setUserAuthenticationRequired: iOS needs no prompt per use (vault-crypto.md §17.3, option 1).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (policy.unlockedDeviceRequired) builder.setUnlockedDeviceRequired(true)
            if (strongBox) builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    private fun generate(algorithm: String, spec: KeyGenParameterSpec): SecretKey =
        KeyGenerator.getInstance(algorithm, PROVIDER).run {
            init(spec)
            generateKey()
        }
}
