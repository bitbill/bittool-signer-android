package com.bittool.signer.core.crypto.vault

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.UUID
import javax.crypto.Cipher

/**
 * The proof that a Keystore AES key is gone for good (QA backlog B9, lost-key items L1–L4), shared by
 * [AndroidKeystoreDeviceKeyStore] (wallet device keys, the public-data key) and [KeystoreJournalCipher] (the Activity
 * journal key), so every "lost" verdict in the app rests on the same evidence.
 *
 * A key is provably lost when [LOSS_CONFIRMATIONS] independent observations, each on a freshly loaded Keystore, agree:
 * - [Loss.Absent]: a probe key generated for this observation is listed before and after the alias reads as absent
 *   from `containsAlias`, `getKey` and `aliases()`. A Keystore that failed to answer reports *every* alias as missing
 *   (the API 26–30 binder pitfall, vault-crypto.md §17.3), so it would lose the probe too; or
 * - [Loss.Invalidated]: the entry exists but Keystore refuses it with `KeyPermanentlyInvalidatedException`.
 *
 * A usable key, any other refusal (a locked device, a transient error) or a disagreement between observations is not a
 * loss. The probe keys protect nothing and are deleted at once (leftovers of a killed process on the next proof). One
 * lock serialises every proof of the process, so one proof never deletes another's probe mid-observation.
 */
internal object KeystoreLossProof {
    /** How a key was proven lost. */
    enum class Loss { Absent, Invalidated }

    /** Throw-away keys of the proof; never under a wallet's alias prefix, so no probe can pass for a wallet's key. */
    const val PROBE_PREFIX = "com.bittool.signer.vault.keystore-probe.v1/"

    /** Independent observations that must agree before a key counts as lost. */
    const val LOSS_CONFIRMATIONS = 3

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AES = "AES"

    private val lock = Any()

    /** The proven loss of the AES key under [alias], or null when it is not proven. Never throws. */
    fun provenLoss(alias: String): Loss? = synchronized(lock) {
        try {
            deleteStaleProbes()
            val first = observe(alias)
            val loss = when (first) {
                Observation.Absent -> Loss.Absent
                Observation.Invalidated -> Loss.Invalidated
                else -> return null
            }
            if ((2..LOSS_CONFIRMATIONS).all { observe(alias) == first }) loss else null
        } catch (_: Exception) {
            // GeneralSecurityException, ProviderException, IOException or anything else Keystore throws: no proof.
            null
        }
    }

    private enum class Observation { Usable, Absent, Invalidated, Unsure }

    /** One look at [alias] on a freshly loaded Keystore. */
    private fun observe(alias: String): Observation {
        val store = Keystore.open()
        if (Keystore.contains(store, alias)) return entryState(store, alias)
        val probe = PROBE_PREFIX + UUID.randomUUID()
        try {
            Keystore.generateProbe(probe)
            if (Keystore.secretKey(store, probe) == null) return Observation.Unsure
            val absent = !Keystore.contains(store, alias) && Keystore.secretKey(store, alias) == null &&
                store.aliases().asSequence().none { it == alias }
            return if (absent && Keystore.contains(store, probe)) Observation.Absent else Observation.Unsure
        } finally {
            try {
                if (Keystore.contains(store, probe)) store.deleteEntry(probe)
            } catch (_: Exception) {
                // A leftover probe protects nothing; the next proof removes it.
            }
        }
    }

    /** An existing entry: usable, permanently invalidated, or something we cannot judge. */
    private fun entryState(store: KeyStore, alias: String): Observation {
        val key = try {
            Keystore.secretKey(store, alias)
        } catch (e: GeneralSecurityException) {
            return if (e.isPermanentInvalidation()) Observation.Invalidated else Observation.Unsure
        } ?: return Observation.Unsure
        if (key.algorithm != AES) return Observation.Unsure
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, key)
                doFinal(ByteArray(0))
            }
            Observation.Usable
        } catch (e: GeneralSecurityException) {
            if (e.isPermanentInvalidation()) Observation.Invalidated else Observation.Unsure
        } catch (_: ProviderException) {
            Observation.Unsure
        }
    }

    private fun Throwable.isPermanentInvalidation(): Boolean =
        generateSequence(this) { it.cause }.take(8).any { it is KeyPermanentlyInvalidatedException }

    /** Probe keys a killed process left behind (each protects nothing). */
    private fun deleteStaleProbes() {
        try {
            val store = Keystore.open()
            store.aliases().toList().filter { it.startsWith(PROBE_PREFIX) }.forEach { store.deleteEntry(it) }
        } catch (_: Exception) {
            // Best effort.
        }
    }
}
