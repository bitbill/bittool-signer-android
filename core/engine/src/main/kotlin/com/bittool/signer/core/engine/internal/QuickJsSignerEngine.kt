package com.bittool.signer.core.engine.internal

import android.content.Context
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

internal class QuickJsSignerEngine(
    private val appContext: Context,
    private val allowTestOperations: Boolean,
) : SignerEngine {

    // Confined to EngineThread.
    private var bundle: EngineBundle? = null
    private var warm: QuickJsContextHost? = null
    private var scanHost: QuickJsContextHost? = null

    override val engineSha256: String get() = EngineBundle.EXPECTED_SHA256
    override val engineBytes: Int get() = ENGINE_BYTES

    override suspend fun run(operation: EngineOperation, parameters: JsonObject): JsonObject {
        if (!operation.production && !allowTestOperations) {
            throw EngineException(EngineException.UNSUPPORTED_OPERATION, "This signing operation is not supported.")
        }
        val json = requestJson(operation.wireName, parameters)
        return withContext(EngineThread.dispatcher) {
            if (operation.isWarm) {
                val host = warm ?: newHost().also { warm = it }
                decode(host.call(json))
            } else {
                newHost().use { decode(it.call(json)) }
            }
        }
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

    override suspend fun scan(frame: String): JsonObject {
        val json = requestJson("scanAdd", JsonObject(mapOf("frame" to JsonPrimitive(frame))))
        return withContext(EngineThread.dispatcher) {
            val host = scanHost ?: throw EngineException(EngineException.SCAN, "The scan session ended. Scan again.")
            decode(host.call(json))
        }
    }

    override suspend fun endScanSession() = withContext(EngineThread.dispatcher) {
        scanHost?.close()
        scanHost = null
    }

    override suspend fun releaseWarmContext() = withContext(EngineThread.dispatcher) {
        warm?.close()
        warm = null
    }

    private fun newHost(): QuickJsContextHost {
        val b = bundle ?: EngineBundle.load(appContext).also { bundle = it }
        return QuickJsContextHost.create(b)
    }

    private fun requestJson(operation: String, parameters: JsonObject): String {
        val request = JsonObject(parameters + ("operation" to JsonPrimitive(operation)))
        val text = Json.encodeToString(JsonElement.serializer(), sortKeys(request))
        if (text.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw EngineException(EngineException.REQUEST_TOO_LARGE, "This request exceeds the 1 MB offline signing limit.")
        }
        return text
    }

    private fun decode(responseJson: String): JsonObject {
        if (responseJson.length > MAX_RESPONSE_BYTES || responseJson.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            throw failure()
        }
        val envelope = try {
            Json.parseToJsonElement(responseJson) as? JsonObject
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
        const val ENGINE_BYTES = 1_181_026

        /** Keys sorted at every level, like iOS `JSONSerialization(.sortedKeys)`. */
        internal fun sortKeys(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sortKeys(it.value) })
            is JsonArray -> JsonArray(element.map(::sortKeys))
            else -> element
        }
    }
}
