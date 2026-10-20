package com.bittool.signer.feature.settings.reference

import com.bittool.signer.feature.settings.SettingsTestData.aavePool
import com.bittool.signer.feature.settings.SettingsTestData.permit2
import com.bittool.signer.feature.settings.SettingsTestData.seaport
import com.bittool.signer.feature.settings.SettingsTestData.universalRouter
import com.bittool.signer.feature.settings.SettingsTestData.usdc
import com.bittool.signer.feature.settings.SettingsTestData.usdt
import com.bittool.signer.feature.settings.SettingsTestData.weth
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.Test

/** The network page search (iOS `NetworkReferenceView.filteredTokens/filteredContracts`, TokenDirectoryView.swift:256-277). */
class ReferenceSearchTest {
    private val tokens = listOf(usdt, usdc, weth)
    private val contracts = listOf(aavePool, universalRouter, permit2, seaport)

    @Test
    fun theQueryIsTrimmedOfSpacesAndTabsAndLowerCased() {
        assertEquals("tether", ReferenceSearch.normalize("  \tTether \t"))
        // Swift `.whitespaces` does not include newlines: they stay part of the query.
        assertEquals("usdt\n", ReferenceSearch.normalize(" USDT\n"))
        // No-break space is Unicode whitespace (Swift trims it too).
        assertEquals("usdt", ReferenceSearch.normalize(" USDT "))
    }

    @Test
    fun lowerCasingIgnoresTheInterfaceLanguage() {
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            // Turkish would turn "I" into dotless "ı"; Swift's lowercased() is locale-independent.
            assertEquals("uniswap ii", ReferenceSearch.normalize("UNISWAP II"))
            assertEquals(listOf(universalRouter), ReferenceSearch.contracts(contracts, ReferenceSearch.normalize("UNIVERSAL")))
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun anEmptyQueryKeepsEveryEntryInFileOrder() {
        assertEquals(tokens, ReferenceSearch.tokens(tokens, ""))
        assertEquals(contracts, ReferenceSearch.contracts(contracts, ReferenceSearch.normalize("   ")))
    }

    @Test
    fun tokensMatchSymbolOrNameAnywhere() {
        // gaps.md G1.18: "tether" → USDT, and not USD Coin.
        assertEquals(listOf(usdt), ReferenceSearch.tokens(tokens, ReferenceSearch.normalize("tether")))
        assertEquals(listOf(usdt, usdc), ReferenceSearch.tokens(tokens, "usd"))
        // "Tether" contains "eth" too: names match anywhere, like iOS `contains`.
        assertEquals(listOf(usdt, weth), ReferenceSearch.tokens(tokens, "eth"))
        assertEquals(listOf(usdc), ReferenceSearch.tokens(tokens, "coin"))
    }

    @Test
    fun tokenAddressesMatchByPrefixOnly() {
        assertEquals(listOf(usdt), ReferenceSearch.tokens(tokens, ReferenceSearch.normalize("0xDAC17F")))
        // "ec7" ends the USDT address: a suffix is not a prefix, so nothing matches.
        assertEquals(emptyList(), ReferenceSearch.tokens(tokens, "831ec7"))
    }

    @Test
    fun contractsMatchNameProjectCategoryOrAddressAnywhere() {
        // gaps.md G1.18: "permit2" → Permit2, and not Seaport.
        assertEquals(listOf(permit2), ReferenceSearch.contracts(contracts, "permit2"))
        assertEquals(listOf(universalRouter, permit2), ReferenceSearch.contracts(contracts, "uniswap"))
        assertEquals(listOf(aavePool), ReferenceSearch.contracts(contracts, "lending"))
        assertEquals(listOf(seaport), ReferenceSearch.contracts(contracts, "nft"))
        // Unlike tokens, a contract address matches anywhere inside it.
        assertEquals(listOf(permit2), ReferenceSearch.contracts(contracts, "22d473"))
    }
}
