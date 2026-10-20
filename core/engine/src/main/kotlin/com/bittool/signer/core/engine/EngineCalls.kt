package com.bittool.signer.core.engine

import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.signing.PlanCandidate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The typed facade over [SignerEngine] (engine-contract.md §12.5): one function per operation the app sends, building
 * exactly the parameters iOS `AppStore` sends (models-state.md §3.1) so call sites cannot drift. Results are the raw
 * engine objects; typed parsing lives in `:core:model` (`WalletPublicMetadata.parse`, `TransactionReview.parse`, …).
 *
 * Rules enforced here:
 * - absent means absent: no Kotlin null is ever put into a request, and `seed` is never sent together with
 *   `mnemonic`/`passphrase` (engine `SEED` error);
 * - the warm operations ([derivePublicAddresses], [createPublicPairing], [createWalletPairing]) take a
 *   [WalletPublicMetadata], i.e. an index that came from the authenticated local repository, never a raw object
 *   decoded from a QR code or a file (engine-contract.md §5.1);
 * - pass-through objects (`request`, `plan`, `publicIndex`, `previousResult`) are forwarded exactly as given.
 *
 * Secrets: [mnemonic]'s entropy and the seeds are `CharArray`s owned by the caller, who wipes them after the call.
 * They exist as a `String` only inside the request JSON, which cannot be wiped (same caveat as iOS, Security.md:72).
 * All functions suspend on the engine thread; none may be called expecting cancellation to interrupt the JS call —
 * results are discarded by the caller's epoch checks instead.
 */
@Singleton
class EngineCalls @Inject constructor(private val engine: SignerEngine) {

    /** `mnemonic {entropy}`: [entropyHex] is `wordCount / 3 * 4` random bytes as lowercase hex. Result: `mnemonic`. */
    suspend fun mnemonic(entropyHex: CharArray): JsonObject =
        engine.run(EngineOperation.Mnemonic, params("entropy" to JsonPrimitive(String(entropyHex))))

    /** `derivePublicIndex {seed}` (fresh runtime): [seedHex] is the 128-hex native BIP39 seed. Result: the public index. */
    suspend fun derivePublicIndex(seedHex: CharArray): JsonObject =
        engine.run(EngineOperation.DerivePublicIndex, params("seed" to JsonPrimitive(String(seedHex))))

    /**
     * `derivePublicAddresses {publicIndex, chain, profile, start, count}` (warm). Profile: the Bitcoin single-sig preset,
     * `""` for Solana (all three presets at once), `"standard"` for EVM and TRON. Result: `addresses`.
     */
    suspend fun derivePublicAddresses(index: WalletPublicMetadata, chain: Chain, profile: String, start: Long, count: Int): JsonObject =
        engine.run(
            EngineOperation.DerivePublicAddresses,
            params(
                "publicIndex" to index.raw,
                "chain" to JsonPrimitive(chain.wire),
                "profile" to JsonPrimitive(profile),
                "start" to JsonPrimitive(start),
                "count" to JsonPrimitive(count),
            ),
        )

    /**
     * `createPublicPairing {publicIndex, chain, profile, outputMode, index}` (warm, no `fragmentLength`: engine default
     * 180). Only TRON exports a non-zero [addressIndex]. Result → `PairingPayload.parse`.
     */
    suspend fun createPublicPairing(index: WalletPublicMetadata, chain: Chain, profile: String, outputMode: String, addressIndex: Long): JsonObject =
        engine.run(
            EngineOperation.CreatePublicPairing,
            params(
                "publicIndex" to index.raw,
                "chain" to JsonPrimitive(chain.wire),
                "profile" to JsonPrimitive(profile),
                "outputMode" to JsonPrimitive(outputMode),
                "index" to JsonPrimitive(addressIndex),
            ),
        )

    /** `createWalletPairing {publicIndex, mode: "full"}` (warm; also the Connect prefetch). Result → `WalletPairingPayload.parse`. */
    suspend fun createWalletPairing(index: WalletPublicMetadata): JsonObject =
        engine.run(EngineOperation.CreateWalletPairing, params("publicIndex" to index.raw, "mode" to JsonPrimitive("full")))

    /** `decodeQR {frames}` (fresh): the batch fallback; production scans use [beginScanSession]/[scan]. */
    suspend fun decodeQr(frames: List<String>): JsonObject =
        engine.run(EngineOperation.DecodeQR, params("frames" to JsonArray(frames.map(::JsonPrimitive))))

    /** `decodePayload {payload}` (fresh): pasted or file text, already checked ≤ 262,144 UTF-8 bytes by the caller. */
    suspend fun decodePayload(text: String): JsonObject =
        engine.run(EngineOperation.DecodePayload, params("payload" to JsonPrimitive(text)))

    /** `review {request}` (fresh): [request] is the frozen request object. Result → `TransactionReview.parse`. */
    suspend fun review(request: JsonObject): JsonObject =
        engine.run(EngineOperation.Review, params("request" to request))

    /**
     * `resolveSigningPlan {request, wallets}` (fresh). Every vault wallet in display order:
     * `{localWalletId, publicIndex}` when its index is ready, else `{localWalletId, publicMetadataStatus}`.
     */
    suspend fun resolveSigningPlan(request: JsonObject, wallets: List<PlanCandidate>): JsonObject =
        engine.run(EngineOperation.ResolveSigningPlan, params("request" to request, "wallets" to candidates(wallets)))

    /**
     * `signPlanTask {request, plan, taskId, seed, riskAcknowledged[, previousResult]}` (fresh). [plan] is
     * `SigningPlan.raw`; [previousResult] is the previous task's whole frozen result (Bitcoin multi-wallet plans).
     */
    suspend fun signPlanTask(
        request: JsonObject,
        plan: JsonObject,
        taskId: String,
        seedHex: CharArray,
        riskAcknowledged: Boolean,
        previousResult: JsonObject?,
    ): JsonObject {
        val entries = mutableListOf<Pair<String, JsonElement>>(
            "request" to request,
            "plan" to plan,
            "taskId" to JsonPrimitive(taskId),
            "seed" to JsonPrimitive(String(seedHex)),
            "riskAcknowledged" to JsonPrimitive(riskAcknowledged),
        )
        if (previousResult != null) entries += "previousResult" to previousResult
        return engine.run(EngineOperation.SignPlanTask, params(*entries.toTypedArray()))
    }

    /** `encodeResponse {request, result}` (fresh, final task only). The result has no `text`: join `frames` with "\n". */
    suspend fun encodeResponse(request: JsonObject, result: JsonObject): JsonObject =
        engine.run(EngineOperation.EncodeResponse, params("request" to request, "result" to result))

    /** A fresh scan-session runtime + `scanBegin` (scanner appears, "Scan Again"). */
    suspend fun beginScanSession() = engine.beginScanSession()

    /** `scanAdd {frame}` with the frame exactly as scanned. Throws `EngineException(SCAN)` without a session. */
    suspend fun scan(frame: String): JsonObject = engine.scan(frame)

    /** Drops the scan-session runtime (scanner dismissed). */
    suspend fun endScanSession() = engine.endScanSession()

    /** Drops the warm runtime (`SignerStore.suspendSession`). */
    suspend fun releaseWarmContext() = engine.releaseWarmContext()

    companion object {
        /** The `wallets` array of `resolveSigningPlan` (public for tests and the store's snapshot logic). */
        fun candidates(wallets: List<PlanCandidate>): JsonArray = JsonArray(
            wallets.map { candidate ->
                val index = candidate.index
                JsonObject(
                    if (index != null) {
                        linkedMapOf("localWalletId" to JsonPrimitive(candidate.walletId.toString()), "publicIndex" to index.raw)
                    } else {
                        linkedMapOf(
                            "localWalletId" to JsonPrimitive(candidate.walletId.toString()),
                            "publicMetadataStatus" to JsonPrimitive(candidate.status.wire),
                        )
                    },
                )
            },
        )

        private fun params(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*entries))
    }
}
