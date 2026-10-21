package com.bittool.signer.core.crypto.vault

import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.json.asLong
import com.bittool.signer.core.model.json.canonicalJson
import com.bittool.signer.core.model.json.obj
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.util.Graphemes
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/** The parsed header (iOS `VaultHeader`, `Core/VaultCrypto.swift:78-84`). */
internal class VaultHeader(
    val version: Int,
    val kdf: String,
    val iterations: Long,
    val salt: ByteArray,
    val metadata: VaultMetadata,
)

/**
 * A vault as stored (iOS `EncryptedVault`, `Core/VaultCrypto.swift:89-118`): [headerBytes] are the exact stored bytes
 * that both AES-GCM layers authenticate — never a re-encoding of [header] ("keeps vaults valid across Foundation
 * changes"); [ciphertext] is the device layer's `iv ‖ ciphertext ‖ tag` over the password layer's combined box.
 */
internal class EncryptedVault(val header: VaultHeader, val headerBytes: ByteArray, val ciphertext: ByteArray)

/**
 * Vault file format 2 (vault-crypto.md §6.2–§6.6; normative byte form in the `WalletVault` KDoc): the container
 * `{"ciphertext":<b64>,"format":2,"header":<b64>}` and the header
 * `{"iterations":600000,"kdf":"PBKDF2-HMAC-SHA256","metadata":{"createdAt":<s since 2001>,"id":<UPPERCASE uuid>,
 * "name":…},"salt":<b64 32 bytes>,"version":2}`, both compact with sorted keys; standard padded base64.
 */
internal object VaultFormat {
    const val FORMAT = 2
    const val VERSION = 2
    const val KDF = "PBKDF2-HMAC-SHA256"
    const val ITERATIONS = 600_000
    val ACCEPTED_ITERATIONS = 600_000L..2_000_000L
    const val SALT_BYTES = 32
    const val MAX_FILE_BYTES = 65_536
    const val MAX_HEADER_BYTES = 4_096
    /** Two AES-GCM layers, each a 12-byte nonce and a 16-byte tag. */
    const val LAYERS_OVERHEAD = 56
    private const val MAX_CIPHERTEXT_BYTES = 32_768
    /** `len(cleartext) + 56` (two GCM layers of nonce 12 + tag 16). */
    val ACCEPTED_CIPHERTEXT = LAYERS_OVERHEAD..MAX_CIPHERTEXT_BYTES
    /** The largest secret JSON whose ciphertext the reader accepts ([ACCEPTED_CIPHERTEXT]). */
    const val MAX_CLEARTEXT_BYTES = MAX_CIPHERTEXT_BYTES - LAYERS_OVERHEAD
    private const val MAX_UINT32 = 0xFFFF_FFFFL

    /** 2001-01-01T00:00:00Z in epoch milliseconds: iOS `Date`'s reference date (`JSONEncoder` `.deferredToDate`). */
    private const val REFERENCE_DATE_MILLIS = 978_307_200_000L

    /** The creation time with the longest `createdAt` text (`-9223372036854775.808`, 21 characters). */
    private const val LONGEST_CREATED_AT_MILLIS = Long.MIN_VALUE + REFERENCE_DATE_MILLIS
    private val TEMPLATE_ID = WalletId(java.util.UUID(0L, 0L))

    private val PASSWORD_PREFIX = "com.bittool.signer.vault.v2/password\u0000".encodeToByteArray()
    private val DEVICE_PREFIX = "com.bittool.signer.vault.v2/device\u0000".encodeToByteArray()

    /** `UTF8("com.bittool.signer.vault.v2/password") ‖ 0x00 ‖ headerBytes` (`Core/VaultCrypto.swift:211-215`). */
    fun passwordAad(headerBytes: ByteArray): ByteArray = PASSWORD_PREFIX + headerBytes

    /** `UTF8("com.bittool.signer.vault.v2/device") ‖ 0x00 ‖ headerBytes`. */
    fun deviceAad(headerBytes: ByteArray): ByteArray = DEVICE_PREFIX + headerBytes

    /** The header bytes, produced once per encryption and never re-serialised afterwards. */
    @OptIn(ExperimentalSerializationApi::class)
    fun encodeHeader(metadata: VaultMetadata, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val header = JsonObject(
            mapOf(
                "iterations" to JsonPrimitive(iterations),
                "kdf" to JsonPrimitive(KDF),
                "metadata" to JsonObject(
                    mapOf(
                        "createdAt" to JsonUnquotedLiteral(formatReferenceSeconds(metadata.createdAtMillis)),
                        "id" to JsonPrimitive(metadata.id.uppercase),
                        "name" to JsonPrimitive(metadata.name),
                    ),
                ),
                "salt" to JsonPrimitive(base64(salt)),
                "version" to JsonPrimitive(VERSION),
            ),
        )
        return header.canonicalJson().encodeToByteArray()
    }

    /**
     * Whether the header of a vault named [name] stays within [MAX_HEADER_BYTES] for any wallet id, salt and creation
     * time: the header is measured with the longest possible `createdAt`, so the answer does not depend on the clock.
     * What counts is the name as the header stores it (escaped, UTF-8): about 3,880 bytes are left for it.
     */
    fun headerFits(name: String): Boolean =
        encodeHeader(VaultMetadata(TEMPLATE_ID, name, LONGEST_CREATED_AT_MILLIS), ByteArray(SALT_BYTES)).size <= MAX_HEADER_BYTES

    /**
     * The stored container [bytes] of wallet [id] as the vault reads it, in iOS `load` order: [decode], [validate], the
     * header id equal to [id]; any other failure is [VaultError.MalformedVault]. Used by the reader and, before every
     * write, by the writer, so the vault never writes a file it would refuse to read back.
     */
    fun decodeStored(id: WalletId, bytes: ByteArray): EncryptedVault {
        val vault = try {
            decode(bytes)
        } catch (e: VaultError) {
            throw e
        } catch (e: RuntimeException) {
            throw VaultError.MalformedVault(e)
        }
        validate(vault)
        if (vault.header.metadata.id != id) throw VaultError.MalformedVault()
        return vault
    }

    fun encodeContainer(headerBytes: ByteArray, ciphertext: ByteArray): ByteArray = JsonObject(
        mapOf(
            "ciphertext" to JsonPrimitive(base64(ciphertext)),
            "format" to JsonPrimitive(FORMAT),
            "header" to JsonPrimitive(base64(headerBytes)),
        ),
    ).canonicalJson().encodeToByteArray()

    /**
     * Decodes a container in iOS order (`EncryptedVault.init(from:)`): `format` ≠ 2 (an integer) is
     * [VaultError.UnsupportedVersion]; header bytes over 4,096, any missing or mistyped field and anything that does
     * not decode is [VaultError.MalformedVault].
     */
    fun decode(bytes: ByteArray): EncryptedVault {
        val container = parseJsonObject(bytes) ?: throw VaultError.MalformedVault()
        val format = container["format"].asLong() ?: throw VaultError.MalformedVault()
        if (format != FORMAT.toLong()) throw VaultError.UnsupportedVersion()
        val headerBytes = container.str("header")?.let(::unbase64) ?: throw VaultError.MalformedVault()
        if (headerBytes.size > MAX_HEADER_BYTES) throw VaultError.MalformedVault()
        val header = decodeHeader(headerBytes) ?: throw VaultError.MalformedVault()
        val ciphertext = container.str("ciphertext")?.let(::unbase64) ?: throw VaultError.MalformedVault()
        return EncryptedVault(header, headerBytes, ciphertext)
    }

    private fun decodeHeader(bytes: ByteArray): VaultHeader? {
        val json = parseJsonObject(bytes) ?: return null
        val version = json["version"].asLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: return null
        val kdf = json.str("kdf") ?: return null
        val iterations = json["iterations"].asLong()?.takeIf { it in 0..MAX_UINT32 } ?: return null
        val salt = json.str("salt")?.let(::unbase64) ?: return null
        val metadata = json.obj("metadata") ?: return null
        val id = WalletId.parse(metadata.str("id")) ?: return null
        val name = metadata.str("name") ?: return null
        val createdAt = (metadata["createdAt"] as? JsonPrimitive)?.takeIf { !it.isString }
            ?.let { parseReferenceSeconds(it.content) } ?: return null
        return VaultHeader(version, kdf, iterations, salt, VaultMetadata(id, name, createdAt))
    }

    /**
     * iOS `VaultCrypto.validate` (`:195-203`): version and KDF → [VaultError.UnsupportedVersion]; salt 32 bytes,
     * iterations 600,000–2,000,000, ciphertext 56–32,768 bytes and a name of 1–40 graphemes → else
     * [VaultError.MalformedVault]. Bounds are checked before any costly derivation.
     */
    fun validate(vault: EncryptedVault) {
        val header = vault.header
        if (header.version != VERSION || header.kdf != KDF) throw VaultError.UnsupportedVersion()
        if (header.salt.size != SALT_BYTES || header.iterations !in ACCEPTED_ITERATIONS ||
            vault.ciphertext.size !in ACCEPTED_CIPHERTEXT || header.metadata.name.isEmpty() ||
            Graphemes.exceeds(header.metadata.name, VaultMetadata.MAX_NAME_LENGTH)
        ) {
            throw VaultError.MalformedVault()
        }
    }

    /** Seconds since 2001-01-01 with millisecond precision as a plain JSON number (`814000000.123`, never `8.1E8`). */
    internal fun formatReferenceSeconds(epochMillis: Long): String =
        BigDecimal.valueOf(Math.subtractExact(epochMillis, REFERENCE_DATE_MILLIS), 3).stripTrailingZeros().toPlainString()

    /** Any JSON number of seconds since 2001 → epoch milliseconds (rounded half-up), or null when out of range. */
    internal fun parseReferenceSeconds(content: String): Long? = try {
        val seconds = BigDecimal(content)
        // At most 16 integer digits (Long milliseconds) and 30 fraction digits: refuse `1e2000000000` or
        // `1e-2000000000` before rescaling would compute a gigantic power of ten.
        if (seconds.scale() !in -16..30 || (seconds.signum() != 0 && seconds.precision() - seconds.scale() > 16)) return null
        val millis = seconds.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact()
        Math.addExact(millis, REFERENCE_DATE_MILLIS)
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Standard base64 with padding only (Foundation `Data(base64Encoded:)`), else null. */
    private fun unbase64(text: String): ByteArray? {
        if (text.length % 4 != 0) return null
        return try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
