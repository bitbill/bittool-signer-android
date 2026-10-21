package com.bittool.signer.feature.settings

import com.bittool.signer.core.common.BuildInfo
import com.bittool.signer.core.common.Distribution
import com.bittool.signer.core.model.registry.KnownContractEntry
import com.bittool.signer.core.model.registry.ReferenceData
import com.bittool.signer.core.model.registry.TokenRegistryEntry
import com.bittool.signer.core.model.registry.TokenRegistryNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Replaces `Dispatchers.Main` with a test dispatcher for ViewModel tests. */
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** A small slice of the bundled registry and contract library (real addresses, public data). */
object SettingsTestData {
    val buildInfo = BuildInfo(
        applicationId = "com.bittool.signer",
        versionName = "1.0.0",
        versionCode = 1_000_001,
        distribution = Distribution.DirectApk,
        isDebug = false,
        repositoryUrl = "https://github.com/bitbill/bittool-signer-android",
        signingCertificateSha256 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    )

    private fun token(chainId: String?, family: String, address: String, name: String, symbol: String, decimals: Int, icon: String) =
        TokenRegistryEntry(family, chainId, address, name, symbol, decimals, icon)

    val usdt = token("1", "evm", "0xdac17f958d2ee523a2206206994597c13d831ec7", "Tether USD", "USDT", 6, "373b13fefbb58245")
    val usdc = token("1", "evm", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USD Coin", "USDC", 6, "1b2c3d4e5f607182")
    val weth = token("1", "evm", "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", "Wrapped Ether", "WETH", 18, "aa2c3d4e5f607182")
    val trxUsdt = token(null, "tron", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "Tether USD", "USDT", 6, "373b13fefbb58245")

    private fun contract(chainId: String?, family: String, address: String, name: String, project: String, category: String) =
        KnownContractEntry(family, chainId, address, name, project, category)

    val permit2 = contract("*", "evm", "0x000000000022d473030f116ddee9f6b43ac78ba3", "Permit2", "Uniswap", "token")
    val seaport = contract("*", "evm", "0x0000000000000068f116a894984e2db1123eb395", "OpenSea Seaport 1.6", "OpenSea", "nft")
    val universalRouter = contract("1", "evm", "0x66a9893cc07d91d95644aedd05d03f95e1dba8af", "Universal Router V4", "Uniswap", "dex")
    val aavePool = contract("1", "evm", "0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2", "Aave V3 Pool", "Aave", "lending")
    val sunSwap = contract(null, "tron", "TKzxdSv2FZKQrEqkKVgp5DcwEXBEKMg2Ax", "SunSwap V2 Router", "SUN", "dex")

    val ethereum = TokenRegistryNetwork("evm", "1", "ethereum", "Ethereum", "ETH", 3)
    val tron = TokenRegistryNetwork("tron", null, "tron", "TRON", "TRX", 1)
    val gnosis = TokenRegistryNetwork("evm", "100", "gnosis", "Gnosis", "xDAI", 0)

    val reference = ReferenceData(
        registryVersion = "2026-09-22.1",
        networks = listOf(ethereum, tron, gnosis),
        tokensByChain = mapOf("evm:1" to listOf(usdt, usdc, weth), "tron:" to listOf(trxUsdt)),
        contractsByChain = mapOf(
            "evm:1" to listOf(aavePool, universalRouter),
            "tron:" to listOf(sunSwap),
            ReferenceData.EVERY_EVM_CHAIN to listOf(seaport, permit2),
        ),
        contractsUpdated = "2026-10-09",
    )
}
