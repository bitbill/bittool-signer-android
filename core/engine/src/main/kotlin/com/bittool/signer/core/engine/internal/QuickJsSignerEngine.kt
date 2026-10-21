package com.bittool.signer.core.engine.internal

import android.content.Context
import com.bittool.signer.core.engine.BundleDigest
import com.bittool.signer.core.engine.EngineException
import com.bittool.signer.core.engine.EngineOperation
import com.bittool.signer.core.engine.SignerEngine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The QuickJS [SignerEngine]. Everything of a call happens on [EngineThread], including building the request: sorting
 * the keys of, encoding and size-checking up to 1 MiB of JSON is as heavy as the response decode, and the store calls
 * from the main thread (QA audit A07/A12; iOS does the same work inside its `SigningEngine` actor). [createHost] makes a
 * runtime from the verified bundle; tests replace it to observe what engine code can reach.
 */
internal class QuickJsSignerEngine(
    private val appContext: Context,
    private val allowTestOperations: Boolean,
    private val createHost: (EngineBundle) -> QuickJsContextHost = QuickJsContextHost::create,
) : SignerEngine {

    // Confined to EngineThread.
    private var bundle: EngineBundle? = null
    private var warm: QuickJsContextHost? = null
    private var scanHost: QuickJsContextHost? = null

    override suspend fun run(operation: EngineOperation, parameters: JsonObject): JsonObject {
        if (!operation.production && !allowTestOperations) {
            throw EngineException(EngineException.UNSUPPORTED_OPERATION, "This signing operation is not supported.")
        }
        return withContext(EngineThread.dispatcher) {
            val json = requestJson(operation.wireName, parameters)
            try {
                runOn(operation, json)
            } finally {
                // The request may carry a seed: wipe this copy (the JsonObject strings themselves cannot be wiped).
                json.fill(0)
            }
        }
    }

    /** On [EngineThread]. */
    private fun runOn(operation: EngineOperation, json: ByteArray): JsonObject =
        if (operation.isWarm) {
            val host = warm ?: newHost().also { warm = it }
            val response = try {
                host.call(json)
            } catch (e: EngineException) {
                // A native failure leaves the runtime in an unknown state: destroy it so the next call starts clean.
                host.close()
                warm = null
                throw e
            }
            decode(response)
        } else {
            newHost().use { decode(it.call(json)) }
        }

    override suspend fun beginScanSession() = withContext(EngineThread.dispatcher) {
        scanHost?.close()
        scanHost = null
        val host = newHost()
        try {
            decode(host.call(requestJson("scanBegin", JsonObject(emptyMap()))))
        } catch (e: Throwable) {
            host.close()
            throw e
        }
        scanHost = host
    }

    override suspend fun scan(frame: String): JsonObject = withContext(EngineThread.dispatcher) {
        val json = requestJson("scanAdd", JsonObject(mapOf("frame" to JsonPrimitive(frame))))
        val host = scanHost ?: throw EngineException(EngineException.SCAN, "The scan session ended. Scan again.")
        val response = try {
            host.call(json)
        } catch (e: EngineException) {
            host.close()
            scanHost = null
            throw e
        }
        decode(response)
    }

    override suspend fun endScanSession() = withContext(EngineThread.dispatcher) {
        scanHost?.close()
        scanHost = null
    }

    override suspend fun releaseWarmContext() = withContext(EngineThread.dispatcher) {
        warm?.close()
        warm = null
    }

    override suspend fun prewarm() = withContext(EngineThread.dispatcher) {
        if (bundle == null) bundle = EngineBundle.load(appContext)
    }

    override suspend fun bundleDigest(): BundleDigest = withContext(EngineThread.dispatcher) {
        val bytes = appContext.assets.open(EngineBundle.ASSET_PATH).use { it.readBytes() }
        BundleDigest(EngineBundle.sha256Hex(bytes), bytes.size, NativeQuickJs.version())
    }

    private fun newHost(): QuickJsContextHost {
        val b = bundle ?: EngineBundle.load(appContext).also { bundle = it }
        return createHost(b)
    }

    /** On [EngineThread]: the sorted-key UTF-8 request, at most [MAX_REQUEST_BYTES]. */
    private fun requestJson(operation: String, parameters: JsonObject): ByteArray {
        val request = JsonObject(parameters + ("operation" to JsonPrimitive(operation)))
        val bytes = Json.encodeToString(JsonElement.serializer(), sortKeys(request)).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_REQUEST_BYTES) {
            bytes.fill(0)
            throw EngineException(EngineException.REQUEST_TOO_LARGE, "This request exceeds the 1 MB offline signing limit.")
        }
        return bytes
    }

    private fun decode(response: ByteArray): JsonObject {
        if (response.size > MAX_RESPONSE_BYTES) throw failure()
        val envelope = try {
            Json.parseToJsonElement(String(response, Charsets.UTF_8)) as? JsonObject
        } catch (t: Throwable) {
            null
        } ?: throw failure()
        val ok = (envelope["ok"] as? JsonPrimitive)?.booleanOrNull ?: throw failure()
        if (!ok) {
            val error = envelope["error"] as? JsonObject
            throw EngineException.bounded(
                (error?.get("code") as? JsonPrimitive)?.contentOrNull,
                (error?.get("message") as? JsonPrimitive)?.contentOrNull,
            )
        }
        return when (val result = envelope["result"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> result
            else -> JsonObject(mapOf("value" to result))
        }
    }

    private fun failure() = EngineException(EngineException.ENGINE_FAILURE, "The offline engine could not process this request.")

    companion object {
        const val MAX_REQUEST_BYTES = 1_048_576
        const val MAX_RESPONSE_BYTES = 2_097_152

        /** Keys sorted at every level, like iOS `JSONSerialization(.sortedKeys)`. */
        internal fun sortKeys(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sortKeys(it.value) })
            is JsonArray -> JsonArray(element.map(::sortKeys))
            else -> element
        }
    }
}
