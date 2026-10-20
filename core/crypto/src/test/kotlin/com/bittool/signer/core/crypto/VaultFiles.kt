package com.bittool.signer.core.crypto

import com.bittool.signer.core.model.WalletId
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Test access to the files a vault writes under a data directory (vault-crypto.md §2.1). */
class VaultFiles(dataDir: File) {
    val directory = File(dataDir, "Vaults")

    fun vault(id: WalletId) = File(directory, "$id.bsvault")
    fun attempts(id: WalletId) = File(directory, ".$id.attempts")

    fun container(id: WalletId): JsonObject = Json.parseToJsonElement(vault(id).readText()).jsonObject

    fun headerBytes(id: WalletId): ByteArray = Base64.getDecoder().decode(container(id).getValue("header").jsonPrimitive.content)

    fun header(id: WalletId): JsonObject = Json.parseToJsonElement(headerBytes(id).decodeToString()).jsonObject

    fun ciphertext(id: WalletId): ByteArray = Base64.getDecoder().decode(container(id).getValue("ciphertext").jsonPrimitive.content)

    /** Rewrites the container with replaced fields (other fields as stored). */
    fun rewrite(
        id: WalletId,
        headerBytes: ByteArray = headerBytes(id),
        ciphertext: ByteArray = ciphertext(id),
        format: JsonElement = container(id).getValue("format"),
    ) {
        val json = JsonObject(
            mapOf(
                "ciphertext" to JsonPrimitive(Base64.getEncoder().encodeToString(ciphertext)),
                "format" to format,
                "header" to JsonPrimitive(Base64.getEncoder().encodeToString(headerBytes)),
            ),
        )
        vault(id).writeText(json.toString())
    }

    /** The header re-encoded from [header] (sorted keys by construction of the map passed in). */
    fun encode(header: JsonObject): ByteArray = header.toString().encodeToByteArray()
}
