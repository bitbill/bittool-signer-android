package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.bip39.Bip39
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.util.Hex
import com.bittool.signer.core.model.util.wipe
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * [SecureRandomSource] over one `java.security.SecureRandom` (Conscrypt's kernel-seeded BoringSSL DRBG on Android;
 * DECISIONS.md §9.6), the port of iOS `SecureRandom` (`Core/VaultCrypto.swift:47-63`). `SecureRandom` is thread-safe.
 */
@Singleton
class SystemSecureRandomSource internal constructor(private val random: SecureRandom) : SecureRandomSource {
    @Inject constructor() : this(SecureRandom())

    override fun bytes(count: Int): ByteArray {
        if (count !in 1..MAX_BYTES) throw VaultError.RandomFailure()
        val out = ByteArray(count)
        try {
            random.nextBytes(out)
        } catch (e: RuntimeException) {
            out.wipe()
            throw VaultError.RandomFailure(e)
        }
        return out
    }

    override fun entropyHex(wordCount: Int): CharArray {
        if (wordCount !in Bip39.SUPPORTED_LENGTHS) throw VaultError.InvalidSecret()
        val entropy = bytes(wordCount / 3 * 4)
        try {
            return Hex.encodeToChars(entropy)
        } finally {
            entropy.wipe()
        }
    }

    override fun asKotlinRandom(): Random = random.asKotlinRandom()

    private companion object {
        /** iOS `bytes(count:)` accepts `1...65_536`. */
        const val MAX_BYTES = 65_536
    }
}
