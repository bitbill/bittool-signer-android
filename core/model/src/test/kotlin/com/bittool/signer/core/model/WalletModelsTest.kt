package com.bittool.signer.core.model

import com.bittool.signer.core.model.json.parseJsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** `WalletPublicMetadata` acceptance (models-state.md §2.3, engine-contract.md §9 item 18) and identity types. */
class WalletModelsTest {
    private fun node(origin: String, depth: Int = 3, key: String = "02" + "11".repeat(32)) = buildJsonObject {
        put("chain", "bitcoin"); put("originPath", origin); put("accountPath", origin); put("path", origin)
        put("xpub", "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V")
        put("publicKey", key); put("compressedPublicKey", key); put("chainCode", "4a".repeat(32)); put("parentFingerprint", "7ef32bdb")
        put("depth", depth); put("scriptProfile", "native"); put("children", JsonArray(listOf(JsonPrimitive("0/*")))); put("masterFingerprint", "73c5da0a")
    }

    private fun tronEntry(i: Int) = buildJsonObject {
        put("chain", "tron"); put("profile", "standard"); put("path", "m/44'/195'/0'/0/$i"); put("index", i); put("branch", 0)
        put("publicKey", "03" + "22".repeat(32)); put("address", "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"); put("masterFingerprint", "73c5da0a")
    }

    private fun solana(profile: String, path: String) = buildJsonObject {
        put("chain", "solana"); put("profile", profile); put("path", path); put("index", 0)
        put("publicKey", "33".repeat(32)); put("address", "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"); put("masterFingerprint", "73c5da0a")
    }

    private fun index(edit: (MutableMap<String, JsonElement>) -> Unit = {}): JsonObject {
        val map = linkedMapOf<String, JsonElement>(
            "version" to JsonPrimitive(1),
            "walletKeyId" to JsonPrimitive("99D5D3536665B81E0E99CCBD2219E090C6EFE01F9966E9E9A7419C23D6D80F96"),
            "masterFingerprint" to JsonPrimitive("73C5DA0A"),
            "bitcoinPublicNodes" to JsonArray(listOf("m/84'/0'/0'", "m/86'/0'/0'", "m/49'/0'/0'", "m/44'/0'/0'").map { node(it) } +
                listOf("m/48'/0'/0'/2'", "m/48'/0'/0'/1'").map { node(it, depth = 4) }),
            "evmAccountNode" to node("m/44'/60'/0'"),
            "tronAccountNode" to node("m/44'/195'/0'"),
            "evmDefaultAddress" to buildJsonObject { put("address", "0x9858EfFD232B4033E47d90003D41EC34EcaEda94") },
            "tronAddressIndex" to JsonArray((0 until 100).map(::tronEntry)),
            "solanaPublicKeys" to JsonArray(listOf(solana("standard", "m/44'/501'/0'/0'"), solana("trust", "m/44'/501'/0'"), solana("legacy", "m/44'/501'"))),
        )
        edit(map)
        return JsonObject(map)
    }

    private fun failure(raw: JsonObject) = assertFailsWith<AppIssue> { WalletPublicMetadata.parse(raw) }.text

    @Test
    fun aValidIndexIsAcceptedAndItsIdentityLowercased() {
        val raw = index()
        val metadata = WalletPublicMetadata.parse(raw)
        assertEquals("99d5d3536665b81e0e99ccbd2219e090c6efe01f9966e9e9a7419c23d6d80f96", metadata.walletKeyId)
        assertEquals("73c5da0a", metadata.masterFingerprint)
        assertEquals("73C5DA0A", metadata.displayFingerprint)
        assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", metadata.evmAddress)
        assertEquals(raw, metadata.raw, "the index is kept exactly as received")
        assertTrue(metadata.canonicalJson.startsWith("{\"bitcoinPublicNodes\":["))
        assertEquals(metadata, WalletPublicMetadata.parse(parseJsonObject(raw.toString())!!))
    }

    @Test
    fun structuralOrSecretContentIsInvalidPublicData() {
        val invalid = UiText.Ios("Invalid public wallet data. Repair this wallet.")
        assertEquals(invalid, failure(index { it["version"] = JsonPrimitive("1") }))
        assertEquals(invalid, failure(index { it["version"] = JsonPrimitive(2) }))
        assertEquals(invalid, failure(index { it["walletKeyId"] = JsonPrimitive("99") }))
        assertEquals(invalid, failure(index { it["bitcoinPublicNodes"] = JsonArray((it["bitcoinPublicNodes"] as JsonArray).drop(1)) }))
        assertEquals(invalid, failure(index { it["tronAddressIndex"] = JsonArray((it["tronAddressIndex"] as JsonArray).drop(1)) }))
        assertEquals(invalid, failure(index { it["Seed"] = JsonPrimitive("x") }))
        assertEquals(invalid, failure(index { it["evmDefaultAddress"] = buildJsonObject { put("nested", buildJsonObject { put("PrivateKey", "x") }) } }))
        assertEquals(invalid, failure(index { it["note"] = JsonPrimitive("xprv9s21ZrQH143K") }))
        assertEquals(invalid, failure(index { it["note"] = JsonPrimitive("x".repeat(2049)) }))
        assertEquals(invalid, failure(index { it["list"] = JsonArray(List(257) { JsonPrimitive(1) }) }))
        var deep: JsonElement = JsonPrimitive(1)
        repeat(13) { deep = JsonArray(listOf(deep)) }
        assertEquals(invalid, failure(index { it["deep"] = deep }))
    }

    @Test
    fun inconsistentNodesAreIncompletePublicData() {
        val inconsistent = UiText.Ios("Public wallet data is incomplete or inconsistent.")
        assertEquals(inconsistent, failure(index { it["evmAccountNode"] = node("m/44'/60'/1'") }))
        assertEquals(inconsistent, failure(index { it["tronAccountNode"] = node("m/44'/195'/0'", depth = 5) }))
        assertEquals(inconsistent, failure(index { it["evmAccountNode"] = node("m/44'/60'/0'", key = "04" + "11".repeat(32)) }))
        assertEquals(inconsistent, failure(index { m ->
            m["tronAddressIndex"] = JsonArray((0 until 100).map { tronEntry(if (it == 99) 0 else it) })
        }))
        assertEquals(inconsistent, failure(index { m ->
            m["solanaPublicKeys"] = JsonArray(listOf(solana("standard", "m/44'/501'/0'/0'"), solana("trust", "m/44'/501'/0'"), solana("legacy", "m/44'/501'/1'")))
        }))
    }

    @Test
    fun anOversizedIndexIsTooLarge() {
        val big = JsonArray(List(200) { JsonPrimitive("z".repeat(2_000)) })
        assertEquals(UiText.Ios("Public wallet data is too large."), failure(index { it["padding"] = big; it["padding2"] = big }))
    }

    @Test
    fun publicAccountsUseIosDefaults() {
        val account = PublicAccount.parse(parseJsonObject("""{"chain":"tron","address":"T1","path":"m/44'/195'/0'/0/3","index":3,"publicKey":"03"}""")!!)
        assertEquals("tron:m/44'/195'/0'/0/3", account.id)
        assertEquals(3L, account.index)
        val empty = PublicAccount.parse(parseJsonObject("""{"chain":"doge","index":"3"}""")!!)
        assertEquals(Chain.Bitcoin, empty.chain)
        assertEquals(0L, empty.index)
        assertEquals("", empty.address)
    }

    @Test
    fun walletIdsParseStrictlyAndPrintLowercase() {
        val id = WalletId.parse("10000000-0000-0000-0000-00000000000A")!!
        assertEquals("10000000-0000-0000-0000-00000000000a", id.toString())
        assertEquals("10000000-0000-0000-0000-00000000000A", id.uppercase)
        assertEquals(id, WalletId.parse("10000000-0000-0000-0000-00000000000a"))
        assertNull(WalletId.parse("1-1-1-1-1"), "UUID.fromString's lenient forms are refused")
        assertNull(WalletId.parse(null))
        assertNotEquals(WalletId.random(), WalletId.random())
    }

    @Test
    fun walletNamesAreTrimmedAndBounded() {
        assertEquals("My Wallet", VaultMetadata.validName("  My Wallet\n"))
        assertNull(VaultMetadata.validName("   "))
        assertEquals("👍🏽".repeat(40), VaultMetadata.validName("👍🏽".repeat(40)))
        assertNull(VaultMetadata.validName("a".repeat(41)))
    }

    @Test
    fun secretTextIsRedactedAndWipeable() {
        val secret = SecretText.of("Strong-Password1")
        assertEquals("SecretText(<redacted>)", secret.toString())
        assertEquals(16, secret.graphemeCount())
        assertTrue(secret.contentEquals(SecretText.of("Strong-Password1")))
        val chars = secret.chars()
        secret.wipe()
        assertTrue(chars.all { it == '\u0000' })
        assertTrue(secret.isEmpty())
        VaultSecret(SecretText.of("abandon"), SecretText.empty()).use { assertEquals("VaultSecret(<redacted>)", it.toString()) }
    }

    @Test
    fun chainsAndBrands() {
        assertEquals(listOf("bitcoin", "evm", "tron", "solana"), Chain.entries.map { it.wire })
        assertEquals(NetworkBrand.Ethereum, Chain.Evm.primaryNetwork)
        assertEquals(NetworkBrand.Base, NetworkBrand.evm("8453"))
        assertNull(NetworkBrand.evm("10"))
        assertNull(NetworkBrand.evm(null))
        assertEquals(Chain.Solana, Chain.fromPairingCode("sol"))
        assertNull(Chain.fromWire("Bitcoin"))
    }
}
