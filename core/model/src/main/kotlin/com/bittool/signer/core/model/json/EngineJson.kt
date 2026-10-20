package com.bittool.signer.core.model.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Strict typed readers in the spirit of iOS `raw["x"] as? T` (models-state.md §0.2, copied from Ownbit's EngineJson):
 * a value of another JSON type reads as absent. A string is never a number, a number is never a string, and a list
 * reader fails as a whole when any element has the wrong type. Nothing is ever coerced.
 */

/** The string at [key], or null when absent or not a JSON string. */
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The integer at [key] (a JSON number without fraction/exponent that fits a Long), else null. */
fun JsonObject.long(key: String): Long? = this[key].asLong()

/** The integer at [key] when it fits an Int (Swift `as? Int` on 64-bit is wider; values above Int.MAX are absent). */
fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

/** The number at [key] as a Double (integers included: engine `progress` may be `0` or `1`), else null. */
fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }?.doubleOrNull

/** The JSON boolean at [key], else null (`1` is not `true`). */
fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let { p -> if (p.content == "true") true else if (p.content == "false") false else null }

/** The object at [key], else null. */
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** The array at [key], else null. */
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** A list that is all strings, else null (iOS `as? [String]`). */
fun JsonObject.strings(key: String): List<String>? = arr(key)?.allStrings()

/** A list that is all integers fitting an Int, else null (iOS `as? [Int]`). */
fun JsonObject.ints(key: String): List<Int>? {
    val array = arr(key) ?: return null
    return array.map { element -> element.asLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: return null }
}

/** A list that is all objects, else null (iOS `as? [[String: Any]]`). */
fun JsonObject.objects(key: String): List<JsonObject>? = arr(key)?.allObjects()

/** Every element as a string, else null. */
fun JsonArray.allStrings(): List<String>? =
    map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }

/** Every element as an object, else null. */
fun JsonArray.allObjects(): List<JsonObject>? = map { it as? JsonObject ?: return null }

/** A JSON integer as Long, else null (strings, booleans, fractions and exponents are not integers). */
fun JsonElement?.asLong(): Long? {
    val p = this as? JsonPrimitive ?: return null
    if (p.isString || p is JsonNull) return null
    val content = p.content
    if (content.any { it == '.' || it == 'e' || it == 'E' }) return null
    return p.longOrNull
}

/**
 * The display text of a review field value: strings as they are, anything else in its JSON form (iOS
 * `String(describing:)`; the engine always sends strings, `engine/src/util.js:31`). JSON null reads as "".
 */
fun JsonElement.displayText(): String = when (this) {
    JsonNull -> ""
    is JsonPrimitive -> content
    else -> toString()
}
