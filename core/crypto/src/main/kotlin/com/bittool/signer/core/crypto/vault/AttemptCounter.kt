package com.bittool.signer.core.crypto.vault

import com.bittool.signer.core.common.storage.AtomicFiles
import com.bittool.signer.core.common.storage.InvalidFileException
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.json.asLong
import com.bittool.signer.core.model.json.canonicalJson
import com.bittool.signer.core.model.json.parseJsonObject
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The persistent failed-attempt counter of one wallet (vault-crypto.md §8, §17.5; iOS
 * `WalletVault.authenticationState/saveAuthenticationState`, `Core/WalletVault.swift:222-251`): the file
 * `Vaults/.<lowercase-uuid>.attempts` holds `DeviceKeyStore.seal(id, {"count":0…16,"retryAfter":<epoch ms>}, aad)`
 * with aad = UTF-8 `"com.bittool.signer.auth.v1/" + UPPERCASE uuid` (no NUL), sealed with the wallet's own device key.
 * Used only under the vault mutex.
 */
internal class AttemptCounter(private val directory: File, private val deviceKeys: DeviceKeyStore) {
    /** [retryAfterMillis] 0 is iOS `.distantPast`. */
    data class State(val count: Int, val retryAfterMillis: Long) {
        companion object {
            val Cleared = State(0, 0)
        }
    }

    fun file(id: WalletId): File = File(directory, ".$id.attempts")

    /**
     * Missing file → [State.Cleared]. Otherwise a regular file ≤ 1,024 bytes that opens with the device key and holds
     * `count ∈ 0…16` and an integer `retryAfter`; anything else is [VaultError.MalformedVault] (fail closed until a
     * reset or delete removes it), except a missing or invalidated device key, which is [VaultError.DeviceKeyUnavailable].
     */
    fun read(id: WalletId): State {
        val sealed = try {
            AtomicFiles.readBounded(file(id), MAX_FILE_BYTES)
        } catch (e: InvalidFileException) {
            throw VaultError.MalformedVault(e)
        } catch (e: IOException) {
            throw VaultError.MalformedVault(e)
        } ?: return State.Cleared
        val plaintext = try {
            deviceKeys.open(id, sealed, aad(id))
        } catch (e: VaultError.DeviceKeyUnavailable) {
            throw e
        } catch (e: VaultError) {
            throw VaultError.MalformedVault(e)
        }
        val json = parseJsonObject(plaintext) ?: throw VaultError.MalformedVault()
        val count = json["count"].asLong()?.takeIf { it in 0..MAX_COUNT } ?: throw VaultError.MalformedVault()
        val retryAfter = json["retryAfter"].asLong() ?: throw VaultError.MalformedVault()
        return State(count.toInt(), retryAfter)
    }

    /**
     * Seals and atomically writes [state]. A missing device key is [VaultError.DeviceKeyUnavailable]; any other
     * failure [VaultError.StorageFailure] (iOS `saveAuthenticationState`).
     */
    fun write(id: WalletId, state: State) {
        val plaintext = JsonObject(
            mapOf("count" to JsonPrimitive(state.count), "retryAfter" to JsonPrimitive(state.retryAfterMillis)),
        ).canonicalJson().encodeToByteArray()
        val sealed = try {
            deviceKeys.seal(id, plaintext, aad(id))
        } catch (e: VaultError.DeviceKeyUnavailable) {
            throw e
        } catch (e: VaultError) {
            throw VaultError.StorageFailure(e)
        }
        try {
            AtomicFiles.write(file(id), sealed)
        } catch (e: IOException) {
            throw VaultError.StorageFailure(e)
        }
    }

    /** Best-effort removal (reset, delete). */
    fun remove(id: WalletId) {
        AtomicFiles.delete(file(id))
    }

    companion object {
        const val MAX_COUNT = 16L
        private const val MAX_FILE_BYTES = 1_024

        fun aad(id: WalletId): ByteArray = "com.bittool.signer.auth.v1/${id.uppercase}".encodeToByteArray()

        /**
         * The next state after a counted attempt (`Core/WalletVault.swift:143-147`): `count = min(previous + 1, 16)`;
         * from the 5th consecutive failure the next attempt waits `min(300, 2^(count − 4))` seconds.
         */
        fun afterAttempt(previous: State, nowMillis: Long): State {
            val count = minOf(previous.count + 1, MAX_COUNT.toInt())
            val delaySeconds = if (count >= 5) minOf(300L, 1L shl (count - 4)) else 0L
            return State(count, nowMillis + delaySeconds * 1_000)
        }

        /** `max(1, ceil(retryAfter − now))` seconds while inside a delay window, else null. */
        fun lockedSeconds(state: State, nowMillis: Long): Long? {
            if (state.retryAfterMillis <= nowMillis) return null
            val remaining = state.retryAfterMillis - nowMillis
            return maxOf(1L, remaining / 1_000 + if (remaining % 1_000 != 0L) 1 else 0)
        }
    }
}
