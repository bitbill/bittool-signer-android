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
 * `Vaults/.<lowercase-uuid>.attempts` holds
 * `DeviceKeyStore.seal(id, {"attemptElapsed":<ms since boot>,"count":0…16,"retryAfter":<epoch ms>}, aad)` with aad =
 * UTF-8 `"com.bittool.signer.auth.v1/" + UPPERCASE uuid` (no NUL), sealed with the wallet's own device key. Used only
 * under the vault mutex.
 *
 * The delay window is measured on `SystemClock.elapsedRealtime()`, not the wall clock (2026-11-16 audit M-02, L-07):
 * an offline phone's date is whatever its owner sets, so moving it forward used to skip every delay, and moving it back
 * locked a wallet for the whole gap. `retryAfter` is still written, for the format iOS shares, but never decides
 * anything. `attemptElapsed` is absent in files written before this change.
 */
internal class AttemptCounter(private val directory: File, private val deviceKeys: DeviceKeyStore) {
    /**
     * [retryAfterMillis] 0 is iOS `.distantPast` (informational only). [attemptElapsedMillis] is the monotonic time of
     * the counted attempt that opened the current window, null when unknown (a file from an earlier version).
     */
    data class State(val count: Int, val retryAfterMillis: Long, val attemptElapsedMillis: Long? = null) {
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
        val attemptElapsed = json["attemptElapsed"]?.let { it.asLong()?.takeIf { ms -> ms >= 0 } ?: throw VaultError.MalformedVault() }
        return State(count.toInt(), retryAfter, attemptElapsed)
    }

    /**
     * Seals and atomically writes [state]. A missing device key is [VaultError.DeviceKeyUnavailable]; any other
     * failure [VaultError.StorageFailure] (iOS `saveAuthenticationState`).
     */
    fun write(id: WalletId, state: State) {
        val fields = buildMap {
            put("count", JsonPrimitive(state.count))
            put("retryAfter", JsonPrimitive(state.retryAfterMillis))
            state.attemptElapsedMillis?.let { put("attemptElapsed", JsonPrimitive(it)) }
        }
        val plaintext = JsonObject(fields).canonicalJson().encodeToByteArray()
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
        const val MAX_DELAY_SECONDS = 300L
        private const val MAX_FILE_BYTES = 1_024

        fun aad(id: WalletId): ByteArray = "com.bittool.signer.auth.v1/${id.uppercase}".encodeToByteArray()

        /** `min(300, 2^(count − 4))` seconds from the 5th consecutive failure, else 0 (`Core/WalletVault.swift:143-147`). */
        fun delaySeconds(count: Int): Long = if (count >= 5) minOf(MAX_DELAY_SECONDS, 1L shl (count - 4)) else 0L

        /**
         * The next state after a counted attempt: `count = min(previous + 1, 16)`, the window opening at [elapsedMillis]
         * (`retryAfter` = [wallMillis] + delay, written for the shared format only).
         */
        fun afterAttempt(previous: State, wallMillis: Long, elapsedMillis: Long): State {
            val count = minOf(previous.count + 1, MAX_COUNT.toInt())
            return State(count, wallMillis + delaySeconds(count) * 1_000, elapsedMillis)
        }

        /**
         * [state] with a window start that is valid in the current boot, or [state] itself when it already is (the
         * caller persists a changed one). `elapsedRealtime` restarts at 0 on reboot, so a start later than [nowElapsed]
         * belongs to an earlier boot: the window then counts from this boot's start, because at least [nowElapsed] has
         * passed since that attempt. A file without a start (written by an earlier version, whose wall-clock
         * `retryAfter` is not trusted) restarts the full delay now.
         */
        fun anchored(state: State, nowElapsed: Long): State {
            if (delaySeconds(state.count) == 0L) return state
            val start = state.attemptElapsedMillis
            return when {
                start == null -> state.copy(attemptElapsedMillis = nowElapsed)
                start > nowElapsed -> state.copy(attemptElapsedMillis = 0)
                else -> state
            }
        }

        /**
         * `max(1, ceil(remaining))` seconds while inside the delay window of an [anchored] state, else null. Because the
         * start is never later than [nowElapsed], the wait never exceeds the delay for the count (at most 300 s).
         *
         * A count without a delay (1–4) is never locked, before any arithmetic on its start: [anchored] leaves such a
         * state as it is, so after a reboot its start belongs to the previous boot, and `start − now` was the previous
         * boot's uptime — one mistyped password and a restart locked the wallet for days (2026-11-17 re-audits: R3
         * BTS-13, M-1).
         */
        fun lockedSeconds(state: State, nowElapsed: Long): Long? {
            val delay = delaySeconds(state.count)
            if (delay == 0L) return null
            val start = state.attemptElapsedMillis ?: return null
            val remaining = start + delay * 1_000 - nowElapsed
            if (remaining <= 0) return null
            return maxOf(1L, remaining / 1_000 + if (remaining % 1_000 != 0L) 1 else 0)
        }
    }
}
