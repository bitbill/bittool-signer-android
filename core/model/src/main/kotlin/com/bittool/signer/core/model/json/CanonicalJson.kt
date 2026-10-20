package com.bittool.signer.core.model.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one JSON configuration for host documents (receipts, sidecars, preferences): unknown keys ignored, nulls and
 * defaults omitted so an optional is absent rather than `null` (models-state.md §6, engine-contract.md §2.4 item 4).
 */
val SignerJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

/** Parses [text] as a JSON object, or null when it is not valid JSON or not an object. */
fun parseJsonObject(text: String): JsonObject? =
    runCatching { SignerJson.parseToJsonElement(text) }.getOrNull() as? JsonObject

/** Parses UTF-8 [bytes] as a JSON object, or null. */
fun parseJsonObject(bytes: ByteArray): JsonObject? =
    runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()?.let(::parseJsonObject)

/**
 * The same value with object keys sorted recursively by UTF-16 code unit (the order JavaScript's `sort()` and iOS
 * `.sortedKeys` give for these keys). Primitive tokens are kept exactly as parsed, so numbers are never re-typed.
 */
fun JsonElement.sortedKeys(): JsonElement = when (this) {
    is JsonObject -> JsonObject(keys.sorted().associateWithTo(LinkedHashMap()) { getValue(it).sortedKeys() })
    is JsonArray -> JsonArray(map { it.sortedKeys() })
    else -> this
}

/**
 * Compact, recursively key-sorted JSON (iOS `JSONSerialization.data(..., [.sortedKeys])`, models-state.md §0.5).
 * Size limits (1 MiB request, 256 KiB public index, 32 KiB receipt payload) are measured on this text's UTF-8 bytes.
 * iOS additionally escapes `/` as `\/`; that is cosmetic because the engine hashes the parsed value.
 */
fun JsonElement.canonicalJson(): String = buildString { writeCanonical(this@canonicalJson) }

private fun StringBuilder.writeCanonical(element: JsonElement) {
    when (element) {
        is JsonObject -> {
            append('{')
            element.keys.sorted().forEachIndexed { i, key ->
                if (i > 0) append(',')
                writeQuoted(key)
                append(':')
                writeCanonical(element.getValue(key))
            }
            append('}')
        }
        is JsonArray -> {
            append('[')
            element.forEachIndexed { i, item ->
                if (i > 0) append(',')
                writeCanonical(item)
            }
            append(']')
        }
        JsonNull -> append("null")
        is JsonPrimitive -> if (element.isString) writeQuoted(element.content) else append(element.content)
    }
}

/**
 * Pretty, key-sorted JSON in Apple's `JSONSerialization` style (`.prettyPrinted, .sortedKeys,
 * .withoutEscapingSlashes`): two-space indent, `"key" : value` with a space on both sides of the colon, one element
 * per line, `/` unescaped, non-ASCII kept as UTF-8. Used for the payload developer views (QRPayloadViewTests).
 * Empty containers print as `{}` / `[]`.
 */
fun JsonElement.prettyJson(): String = buildString { writePretty(this@prettyJson, 0) }

private fun StringBuilder.writePretty(element: JsonElement, depth: Int) {
    when (element) {
        is JsonObject -> {
            if (element.isEmpty()) { append("{}"); return }
            append("{\n")
            element.keys.sorted().forEachIndexed { i, key ->
                if (i > 0) append(",\n")
                indent(depth + 1)
                writeQuoted(key)
                append(" : ")
                writePretty(element.getValue(key), depth + 1)
            }
            append('\n')
            indent(depth)
            append('}')
        }
        is JsonArray -> {
            if (element.isEmpty()) { append("[]"); return }
            append("[\n")
            element.forEachIndexed { i, item ->
                if (i > 0) append(",\n")
                indent(depth + 1)
                writePretty(item, depth + 1)
            }
            append('\n')
            indent(depth)
            append(']')
        }
        JsonNull -> append("null")
        is JsonPrimitive -> if (element.isString) writeQuoted(element.content) else append(element.content)
    }
}

private fun StringBuilder.indent(depth: Int) {
    repeat(depth) { append("  ") }
}

/** JSON string escaping: `"`, `\`, and control characters; everything else (including `/` and non-ASCII) verbatim. */
private fun StringBuilder.writeQuoted(value: String) {
    append('"')
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c.code < 0x20) {
                append("\\u")
                append(c.code.toString(16).padStart(4, '0'))
            } else {
                append(c)
            }
        }
    }
    append('"')
}
