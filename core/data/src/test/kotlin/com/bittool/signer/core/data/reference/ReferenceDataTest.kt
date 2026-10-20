package com.bittool.signer.core.data.reference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.data.FakeEngine
import com.bittool.signer.core.model.json.SignerJson
import com.bittool.signer.core.model.registry.KnownContractEntry
import com.bittool.signer.core.model.registry.ReferenceData
import com.bittool.signer.core.model.registry.TokenRegistryEntry
import com.bittool.signer.core.model.registry.TokenRegistryNetwork
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bundled registry and known-contract library as Settings reads them: iOS KnownContractTests (the three library
 * cases) and TokenRegistryBundleTests.registryCoversEveryNetworkItListsWithTheCountsItClaims, over
 * [AssetReferenceDataRepository] and the asset files themselves.
 */
@RunWith(RobolectricTestRunner::class)
class ReferenceDataTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Serializable
    private data class ContractsFile(
        val schemaVersion: Int,
        val source: String,
        val updated: String,
        val categories: List<String>,
        val counts: Map<String, Int>,
        val contracts: List<KnownContractEntry>,
    )

    @Serializable
    private data class RegistryFile(val registryVersion: String, val networks: List<TokenRegistryNetwork>, val assets: List<TokenRegistryEntry>)

    private fun text(path: String) = context.assets.open(path).use { it.readBytes() }.decodeToString()
    private fun library() = SignerJson.decodeFromString(ContractsFile.serializer(), text(AssetReferenceDataRepository.KNOWN_CONTRACTS))
    private fun registry() = SignerJson.decodeFromString(RegistryFile.serializer(), text(AssetReferenceDataRepository.TOKEN_REGISTRY))

    private fun repository(test: TestScope): AssetReferenceDataRepository {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        return AssetReferenceDataRepository(context, FakeEngine(), dispatcher, dispatcher)
    }

    @Test
    fun everyRecordIsCompleteUniqueAndCountedAsTheFileClaims() {
        val file = library()
        assertEquals(1, file.schemaVersion)
        assertTrue(file.contracts.size > 1_500)
        assertEquals(10, file.updated.length, "an ISO date the Settings footer can show")
        val categories = file.categories.toSet()
        val perChain = HashMap<String, Int>()
        val seen = HashSet<String>()
        for (entry in file.contracts) {
            assertTrue(entry.name.isNotEmpty(), entry.id)
            assertTrue(entry.project.isNotEmpty(), entry.id)
            assertTrue(entry.category in categories, entry.id)
            assertTrue(seen.add(entry.id), "duplicate ${entry.id}")
            perChain.merge(entry.chain, 1, Int::plus)
            when (entry.chainFamily) {
                // Stored lowercased so a lookup never depends on checksum case.
                "evm" -> assertTrue(entry.address == entry.address.lowercase() && entry.address.length == 42 && entry.address.startsWith("0x"), entry.id)
                "tron" -> assertTrue(entry.address.startsWith("T") && entry.address.length == 34, entry.id)
                "solana" -> assertTrue(entry.address.length in 32..44, entry.id)
                else -> error("unexpected chain family ${entry.chainFamily}")
            }
        }
        assertEquals(file.counts, perChain, "the declared counts are what the Settings page shows")
    }

    @Test
    fun everyListedChainIsReachableFromTheDirectory() = runTest {
        val data = repository(this).load()
        assertTrue(data.contractCount > 1_500)
        assertTrue(data.contractsUpdated.isNotEmpty())
        val reachable = data.networks.map { it.chain }.toSet() + ReferenceData.EVERY_EVM_CHAIN
        for (chain in data.contractsByChain.keys) assertTrue(chain in reachable, "no network row shows $chain")
        // Deterministic deployments are offered as their own row.
        assertTrue(data.everyChainContracts.size > 20)
        assertTrue(data.everyChainContracts.all { it.everyChain })
        // Sorted by project, then name: a 300-row list is read by eye.
        val ethereum = assertNotNull(data.networks.firstOrNull { it.chain == "evm:1" })
        val contracts = data.contractsByChain.getValue(ethereum.chain)
        val names = contracts.map { it.project to it.name }
        assertTrue(names.size > 300)
        assertEquals(names.sortedWith(compareBy({ it.first }, { it.second })), names)
        assertTrue(contracts.none { it.everyChain })
    }

    @Test
    fun wellKnownAddressesAreListedWhereTheyAreExpected() {
        val file = library()
        fun entry(chain: String, address: String) = file.contracts.firstOrNull { it.chain == chain && it.address.equals(address, ignoreCase = true) }
        // Permit2 and Seaport 1.6 are CREATE2 deployments at one address everywhere.
        val permit2 = assertNotNull(entry("evm:*", "0x000000000022d473030f116ddee9f6b43ac78ba3"))
        assertTrue(permit2.everyChain)
        assertEquals("Uniswap", permit2.project)
        assertEquals("OpenSea", entry("evm:*", "0x0000000000000068f116a894984e2db1123eb395")?.project)
        // Chain-specific entries stay on their chain.
        assertTrue(entry("evm:1", "0x00000000000001ad428e4906ae43d8f9852d0dd6")?.name?.contains("Seaport") == true)
        assertEquals("infra", entry("solana:", "11111111111111111111111111111111")?.category)
        assertEquals("JustLend DAO", entry("tron:", "TA3q7XjdBQWb4qFxaPULUsnjvVZGgC9Brz")?.project)
    }

    @Test
    fun registryCoversEveryNetworkItListsWithTheCountsItClaims() {
        val file = registry()
        assertTrue(file.assets.size > 3_000)
        assertEquals(10, file.networks.size)
        val perChain = file.assets.groupingBy { it.chain }.eachCount()
        for (network in file.networks) {
            assertEquals(network.tokens, perChain[network.chain], network.key)
            assertTrue(network.name.isNotEmpty() && network.nativeSymbol.isNotEmpty())
        }
        // The four EVM networks with wallet-level artwork, plus TRON and Solana.
        assertEquals(6, file.networks.mapNotNull { it.brand }.size)
        assertNull(file.networks.first { it.key == "polygon" }.brand)
    }

    @Test
    fun theRepositoryGroupsTokensInFileOrderAndCachesTheResult() = runTest {
        val repository = repository(this)
        val data = repository.load()
        val file = registry()
        assertEquals(file.registryVersion, data.registryVersion)
        assertEquals(file.networks, data.networks)
        assertEquals(file.assets.filter { it.chain == "evm:1" }, data.tokensByChain["evm:1"])
        assertSame(data, repository.load(), "Decoded once per process.")
        assertEquals("2026-09-22.1", repository.registryVersion())
        assertEquals(FakeEngine().bundleDigest(), repository.engineDigest())
    }

    @Test
    fun contractOrderingComparesUnicodeScalars() {
        assertTrue(AssetReferenceDataRepository.compareScalars("a", "b") < 0)
        assertTrue(AssetReferenceDataRepository.compareScalars("ab", "a") > 0)
        // U+FF5E sorts before U+1F600 by scalar, after it by UTF-16 unit.
        assertTrue(AssetReferenceDataRepository.compareScalars("～", "😀") < 0)
        assertTrue("～" > "😀")
    }
}
