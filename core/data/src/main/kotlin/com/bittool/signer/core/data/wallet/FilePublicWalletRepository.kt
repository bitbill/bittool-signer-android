package com.bittool.signer.core.data.wallet

import com.bittool.signer.core.common.coroutines.IoDispatcher
import com.bittool.signer.core.common.storage.AtomicFiles
import com.bittool.signer.core.common.storage.SignerDataDir
import com.bittool.signer.core.crypto.vault.PublicDataKey
import com.bittool.signer.core.crypto.vault.SignerFiles
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.PublicWalletRepository
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.PublicWalletSnapshot
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.json.canonicalJson
import com.bittool.signer.core.model.json.int
import com.bittool.signer.core.model.json.obj
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.Hex
import com.bittool.signer.core.model.util.trimWhitespacesAndNewlines
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The authenticated public sidecars (port of iOS `Services/PublicWalletRepository.swift`, vault-crypto.md §10).
 *
 * Public keys, addresses and display names for each wallet. They cannot spend funds but reveal account relationships
 * and drive the receive addresses people share, so every file is authenticated with the Keystore public-data HMAC key.
 * A missing, damaged or modified file never triggers seed decryption: the wallet shows "Repair".
 *
 * File `<dir>/<lowercase-uuid>.json` = `{"body":<std base64 of bodyBytes>,"mac":<64 lowercase hex>,"schemaVersion":2}`
 * (sorted keys), `bodyBytes` = sorted-keys JSON `{"displayName"?, "localWalletId", "publicIndex"?}` (≤ 300,000 bytes),
 * MAC = `HMAC-SHA256(key, "com.bittool.signer.public-wallet.v2" ‖ 0x00 ‖ lowercase uuid ‖ 0x00 ‖ bodyBytes)`. Android
 * writes `localWalletId` in the canonical lowercase spelling (WalletId); reads compare it as a UUID, either case.
 * Files are written atomically ([AtomicFiles]) and read with a size bound before parsing; all operations are
 * serialised by one [Mutex] (iOS `actor`) and run on [io].
 */
@Singleton
class FilePublicWalletRepository @Inject constructor(
    @SignerDataDir dataDir: File,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PublicWalletRepository {

    /** `@SignerDataDir/PublicWallets`. */
    val directory: File = File(dataDir, SignerFiles.PUBLIC_WALLETS_DIR)

    private val mutex = Mutex()
    private var tidied = false

    override suspend fun snapshot(walletId: WalletId, key: PublicDataKey?): PublicWalletSnapshot = locked {
        // The status table of vault-crypto.md §10.5 (PublicWalletRepository.swift:27-41).
        if (!file(walletId).exists()) return@locked PublicWalletSnapshot(null, null, PublicMetadataStatus.NeedsCompletion)
        if (key == null) return@locked INVALID
        try {
            val body = read(walletId, key)
            val name = body.str("displayName")
            if (name != null && !validName(name)) return@locked INVALID
            val index = body.obj("publicIndex")
                ?: return@locked PublicWalletSnapshot(name, null, PublicMetadataStatus.NeedsCompletion)
            PublicWalletSnapshot(name, WalletPublicMetadata.parse(index), PublicMetadataStatus.Ready)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            INVALID
        }
    }

    override suspend fun save(metadata: WalletPublicMetadata, walletId: WalletId, displayName: String?, key: PublicDataKey) =
        locked {
            // PublicWalletRepository.swift:43-51: the given name, else the name of a valid existing file.
            val name = displayName ?: readOrNull(walletId, key)?.str("displayName")
            val body = linkedMapOf<String, JsonElement>(
                "localWalletId" to JsonPrimitive(walletId.toString()),
                "publicIndex" to metadata.raw,
            )
            if (name != null) {
                if (!validName(name)) throw VaultError.InvalidName()
                body["displayName"] = JsonPrimitive(name)
            }
            write(JsonObject(body), walletId, key)
        }

    override suspend fun rename(walletId: WalletId, name: String, key: PublicDataKey) = locked {
        // PublicWalletRepository.swift:53-59: an unreadable existing file loses its index (the wallet needs repair).
        val trimmed = name.trimWhitespacesAndNewlines()
        if (!validName(trimmed)) throw VaultError.InvalidName()
        val body = LinkedHashMap<String, JsonElement>(
            readOrNull(walletId, key) ?: JsonObject(mapOf("localWalletId" to JsonPrimitive(walletId.toString()))),
        )
        body["displayName"] = JsonPrimitive(trimmed)
        write(JsonObject(body), walletId, key)
    }

    override suspend fun remove(walletId: WalletId) = locked {
        val target = file(walletId)
        if (target.exists() && !target.delete() && target.exists()) throw IOException("The public wallet file could not be removed.")
    }

    /** The sidecar of [walletId] (tests inspect and tamper with it). */
    fun file(walletId: WalletId): File = File(directory, "$walletId.json")

    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock {
        withContext(io) {
            if (!tidied) {
                tidied = true
                AtomicFiles.deleteStalePending(directory)
            }
            block()
        }
    }

    /** iOS `read` (`:79-98`): every condition of vault-crypto.md §10.4, else "Invalid public wallet data.". */
    private fun read(walletId: WalletId, key: PublicDataKey): JsonObject {
        val bytes = try {
            AtomicFiles.readBounded(file(walletId), MAX_FILE_BYTES)
        } catch (e: IOException) {
            null
        } ?: throw invalidData()
        val envelope = parseJsonObject(bytes) ?: throw invalidData()
        if (envelope.int("schemaVersion") != SCHEMA_VERSION) throw invalidData()
        val body = envelope.str("body")?.let(::decodeBase64) ?: throw invalidData()
        val tag = envelope.str("mac")?.takeIf { it.length == 64 }?.let(Hex::decode) ?: throw invalidData()
        if (tag.size != 32 || !key.verify(macInput(walletId, body), tag)) throw invalidData()
        val raw = parseJsonObject(body) ?: throw invalidData()
        if (WalletId.parse(raw.str("localWalletId")) != walletId) throw invalidData()
        return raw
    }

    /** iOS `try? read(…)`: any failure is "no readable file". */
    private fun readOrNull(walletId: WalletId, key: PublicDataKey): JsonObject? = try {
        read(walletId, key)
    } catch (e: Exception) {
        null
    }

    /** iOS `write` (`:100-117`): size check, MAC, sorted envelope, atomic replace. */
    private fun write(body: JsonObject, walletId: WalletId, key: PublicDataKey) {
        val bodyBytes = body.canonicalJson().encodeToByteArray()
        if (bodyBytes.size > MAX_BODY_BYTES) throw AppIssue("Public wallet data is too large.")
        val envelope = JsonObject(
            mapOf(
                "body" to JsonPrimitive(Base64.getEncoder().encodeToString(bodyBytes)),
                "mac" to JsonPrimitive(Hex.encode(key.mac(macInput(walletId, bodyBytes)))),
                "schemaVersion" to JsonPrimitive(SCHEMA_VERSION),
            ),
        )
        try {
            AtomicFiles.write(file(walletId), envelope.canonicalJson().encodeToByteArray())
        } catch (e: IOException) {
            throw VaultError.StorageFailure(e)
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2
        const val MAX_FILE_BYTES = 600_000
        const val MAX_BODY_BYTES = 300_000
        private const val DOMAIN = "com.bittool.signer.public-wallet.v2"

        private val INVALID = PublicWalletSnapshot(null, null, PublicMetadataStatus.Invalid)

        /** `DOMAIN ‖ 0x00 ‖ lowercase uuid ‖ 0x00 ‖ body` (PublicWalletRepository.swift:71-77). */
        internal fun macInput(walletId: WalletId, body: ByteArray): ByteArray {
            val domain = DOMAIN.encodeToByteArray()
            val id = walletId.toString().encodeToByteArray()
            val out = ByteArray(domain.size + 1 + id.size + 1 + body.size)
            domain.copyInto(out, 0)
            id.copyInto(out, domain.size + 1)
            body.copyInto(out, domain.size + 1 + id.size + 1)
            return out
        }

        /**
         * iOS `validName` (`:66-68`): non-empty after trimming whitespaces and newlines, and at most 40 grapheme clusters
         * **as given** (iOS counts the untrimmed name).
         */
        internal fun validName(name: String): Boolean =
            name.trimWhitespacesAndNewlines().isNotEmpty() && !Graphemes.exceeds(name, 40)

        /**
         * Strict standard base64, as Foundation `Data(base64Encoded:)` reads it (`PublicWalletRepository.swift:86`):
         * whitespace, other alphabets and bad padding are refused (the JDK decoder does that), and so is missing padding,
         * which the JDK decoder would accept.
         */
        internal fun decodeBase64(text: String): ByteArray? {
            if (text.length % 4 != 0) return null
            return try {
                Base64.getDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun invalidData() = AppIssue("Invalid public wallet data.")
    }
}
