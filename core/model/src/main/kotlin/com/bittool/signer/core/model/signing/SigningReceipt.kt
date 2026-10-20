package com.bittool.signer.core.model.signing

import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.json.SignerJson
import com.bittool.signer.core.model.review.RiskLevel
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.Hex
import com.bittool.signer.core.model.util.utf8Size
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A local record of one signature created on this device (models-state.md §6, iOS `Models.swift:404-459`). It never
 * stores secrets and never claims broadcast or confirmation. The JSON keys match iOS; [date] is epoch milliseconds
 * (Android never reads iOS files). Optionals are omitted from the JSON when null.
 */
@Serializable
data class SigningReceipt(
    val id: String,
    /** Signing time, epoch milliseconds. */
    val date: Long,
    @Serializable(with = ChainWireSerializer::class) val chain: Chain,
    /** The review title (engine English), at most 200 graphemes. */
    val title: String,
    val requestHash: String,
    @SerialName("walletID") val walletId: String? = null,
    @SerialName("walletKeyID") val walletKeyId: String? = null,
    /** Lowercase 8-hex master fingerprint at signing time. */
    val masterFingerprint: String? = null,
    /** [RESULT_SIGNATURE] or [RESULT_PARTIAL]; null on records written without it (UI shows "Signature created"). */
    val resultType: String? = null,
    /** At most 160 graphemes at write time (read limit 200). */
    val summary: String? = null,
    val networkName: String? = null,
    /** EVM chain ID, for the row logo. */
    @SerialName("chainID") val chainId: String? = null,
    /** Raw [RiskLevel] wire value. */
    val riskLevel: String? = null,
    /** The canonical frozen request JSON, for re-review in Activity. */
    @SerialName("requestJSON") val requestJson: String? = null,
    /** The signature QR frames (final task only). */
    val responseFrames: List<String>? = null,
    /** The export text: frames joined by "\n" (final task only). */
    val responseText: String? = null,
) {
    /** Whether anything beyond the summary was kept. */
    val hasPayloads: Boolean get() = requestJson != null || responseText != null || responseFrames?.isEmpty() == false

    /** The same record without its payloads (ageing out). */
    fun withoutPayloads(): SigningReceipt = copy(requestJson = null, responseFrames = null, responseText = null)

    val walletIdOrNull: WalletId? get() = WalletId.parse(walletId)
    val risk: RiskLevel? get() = RiskLevel.fromWire(riskLevel)
    val isPartial: Boolean get() = resultType == RESULT_PARTIAL

    companion object {
        /** UTF-8 byte limit of one payload. */
        const val PAYLOAD_LIMIT = 32_768
        /** How many of the most recent signatures keep their payloads. */
        const val PAYLOAD_RETENTION = 25
        const val MAX_FRAMES = 2_048
        const val RESULT_SIGNATURE = "Signature created"
        const val RESULT_PARTIAL = "Partial signature created"
        const val MAX_SUMMARY_WRITE = 160
        const val MAX_TEXT_READ = 200

        /** A payload worth storing: non-null, non-empty, ≤ 32,768 UTF-8 bytes; else null (never truncated). */
        fun stored(text: String?): String? = text?.takeIf { it.isNotEmpty() && it.utf8Size() <= PAYLOAD_LIMIT }

        /** Frames worth storing: non-empty, ≤ 2,048 frames, ≤ 32,768 UTF-8 bytes in total; else null. */
        fun stored(frames: List<String>?): List<String>? =
            frames?.takeIf { it.isNotEmpty() && it.size <= MAX_FRAMES && it.sumOf { f -> f.utf8Size() } <= PAYLOAD_LIMIT }

        /**
         * The record `sign` appends after a task (models-state.md §5.4 step 10): a new id, the approved (refined)
         * review's title clamped to 200 graphemes, its summary cut to 160, network name/ID and risk, the request
         * payload, and the response payloads for the final task only.
         */
        fun create(
            nowMillis: Long,
            task: SigningTask,
            review: TransactionReview,
            planRequestHash: String,
            masterFingerprint: String?,
            finalTask: Boolean,
            requestJson: String,
            signatureFrames: List<String>,
            signatureText: String,
            id: String = UUID.randomUUID().toString(),
        ): SigningReceipt = SigningReceipt(
            id = id,
            date = nowMillis,
            chain = task.chain,
            title = Graphemes.prefix(review.title, MAX_TEXT_READ),
            requestHash = planRequestHash,
            walletId = task.walletId.toString(),
            walletKeyId = task.walletKeyId,
            masterFingerprint = masterFingerprint,
            resultType = if (finalTask) RESULT_SIGNATURE else RESULT_PARTIAL,
            summary = Graphemes.prefix(review.summary, MAX_SUMMARY_WRITE),
            networkName = review.networkName,
            chainId = review.network?.chainId,
            riskLevel = review.riskLevel.wire,
            requestJson = stored(requestJson),
            responseFrames = if (finalTask) stored(signatureFrames) else null,
            responseText = if (finalTask) stored(signatureText) else null,
        )
    }
}

/**
 * The bounded Activity journal (models-state.md §6, iOS `AppStore.swift:532-601`): pure encode/decode/retention rules;
 * `:core:data` seals the bytes and does the file I/O (`noBackupFilesDir/BitToolSigner/receipts.bin`, DECISIONS §9.3).
 */
object ReceiptJournal {
    /** Largest journal file accepted on read and produced on write. */
    const val FILE_LIMIT = 2_097_152
    const val MAX_RECORDS = 100

    private val serializer = ListSerializer(SigningReceipt.serializer())

    /** JSON array, newest first. */
    fun encode(records: List<SigningReceipt>): ByteArray = SignerJson.encodeToString(serializer, records).encodeToByteArray()

    /**
     * Reads a journal file. Throws [AppIssue] "Invalid activity record." when it is over 2 MiB, does not decode, has
     * more than 100 records, or any record breaks a limit (title/summary ≤ 200 graphemes, 64-hex `requestHash`, strict
     * 36-character UUID `id` and `walletID` like Swift `UUID(uuidString:)` — `java.util.UUID.fromString` would accept
     * "1-1-1-1-1" —, payloads already in [SigningReceipt.stored] form). A missing file is the caller's empty list.
     */
    fun decode(bytes: ByteArray): List<SigningReceipt> {
        if (bytes.size > FILE_LIMIT) throw invalid()
        val records = try {
            SignerJson.decodeFromString(serializer, bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (e: SerializationException) {
            throw invalid()
        } catch (e: IllegalArgumentException) {
            throw invalid()
        } catch (e: CharacterCodingException) {
            throw invalid()
        }
        if (!isValid(records)) throw invalid()
        return records
    }

    /** Whether every read-side rule holds for [records]. */
    fun isValid(records: List<SigningReceipt>): Boolean = records.size <= MAX_RECORDS && records.all { r ->
        !Graphemes.exceeds(r.title, SigningReceipt.MAX_TEXT_READ) &&
            !Graphemes.exceeds(r.summary ?: "", SigningReceipt.MAX_TEXT_READ) &&
            Hex.isHash64(r.requestHash) &&
            WalletId.parse(r.id) != null &&
            (r.walletId == null || WalletId.parse(r.walletId) != null) &&
            SigningReceipt.stored(r.requestJson) == r.requestJson &&
            SigningReceipt.stored(r.responseText) == r.responseText &&
            SigningReceipt.stored(r.responseFrames) == r.responseFrames
    }

    /**
     * iOS `appendReceipt`: `[new] + existing`, the first 100; records at index ≥ 25 lose their payloads; then, while
     * the encoding exceeds 2 MiB and a record still has payloads, the oldest such record loses them.
     */
    fun appended(new: SigningReceipt, existing: List<SigningReceipt>): List<SigningReceipt> {
        val updated = (listOf(new) + existing).take(MAX_RECORDS)
            .mapIndexed { i, r -> if (i < SigningReceipt.PAYLOAD_RETENTION) r else r.withoutPayloads() }
            .toMutableList()
        while (encode(updated).size > FILE_LIMIT) {
            val oldest = updated.indexOfLast { it.hasPayloads }
            if (oldest < 0) break
            updated[oldest] = updated[oldest].withoutPayloads()
        }
        return updated
    }

    private fun invalid() = AppIssue("Invalid activity record.")
}

/** Encodes [Chain] by its wire value; an unknown value fails decoding (the whole journal is then invalid, like iOS). */
internal object ChainWireSerializer : KSerializer<Chain> {
    override val descriptor = PrimitiveSerialDescriptor("com.bittool.signer.Chain", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Chain) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): Chain {
        val wire = decoder.decodeString()
        return Chain.fromWire(wire) ?: throw SerializationException("Unknown chain")
    }
}
