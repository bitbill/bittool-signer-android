package com.bittool.signer.core.model.review

import com.bittool.signer.core.model.AddressFormatter
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.NetworkBrand
import com.bittool.signer.core.model.json.parseJsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * Ports of the review parts of `KnownContractTests.swift` (ReviewContract and the summary notice),
 * `HardeningTests.swift` (presentation helpers) and `ActivityRecordTests.theDecodedEssentials…`.
 */
class ReviewModelTest {
    private fun contract(json: String) = ReviewContract.parse(parseJsonObject(json))
    private fun review(json: String) = TransactionReview.parse(parseJsonObject(json)!!)

    @Test
    fun aLibraryHitCarriesTheIdentificationItIsShownWith() {
        val hit = assertNotNull(contract("""{"address":"0x000000000022D473030F116dDEE9F6B43aC78BA3","role":"spender","known":true,"name":"Uniswap Permit2","protocol":"Uniswap","category":"infra","everyChain":true}"""))
        assertTrue(hit.known)
        assertEquals(ReviewContract.Role.Spender, hit.role)
        assertEquals("Uniswap Permit2", hit.name)
        assertEquals("Uniswap · infra", hit.subtitle)
        assertTrue(hit.everyChain)
        assertNull(hit.token)
        assertEquals(ContractNoticeKind.Known, hit.noticeKind)
        assertEquals("Uniswap Permit2 — Uniswap · infra", hit.noticeDetail)
    }

    @Test
    fun anUnlistedAddressIsStatedAsUnlistedAndKeepsItsRole() {
        val miss = assertNotNull(contract("""{"address":"0xBAbAbabAbabaBABaBAbABabaBAbAbaBaBAbABaBa","role":"operator","known":false}"""))
        assertFalse(miss.known)
        assertEquals(ReviewContract.Role.Operator, miss.role)
        assertNull(miss.name)
        assertNull(miss.subtitle)
        assertFalse(miss.everyChain)
        assertEquals(ContractNoticeKind.UnlistedSpender, miss.noticeKind)
        assertNull(miss.noticeDetail)
    }

    @Test
    fun aRegistryTokenIsNamedInsteadOfCalledUnknown() {
        val token = assertNotNull(contract("""{"address":"0xdAC17F958D2ee523a2206206994597C13D831ec7","role":"contract","known":false,"token":"Tether USD (USDT)"}"""))
        assertFalse(token.known)
        assertEquals("Tether USD (USDT)", token.token)
        assertEquals(ReviewContract.Role.Contract, token.role)
        assertEquals(ContractNoticeKind.Token, token.noticeKind)
        assertEquals("Tether USD (USDT)", token.noticeDetail)
    }

    @Test
    fun knownWithoutANameIsNotPresentedAsAHit() {
        val nameless = assertNotNull(contract("""{"address":"0x1234567890AbcdEF1234567890aBcdef12345678","role":"spender","known":true,"protocol":"Mystery"}"""))
        assertFalse(nameless.known)
        assertNull(nameless.name)
        assertNull(nameless.subtitle)
    }

    @Test
    fun anUnknownRoleFallsBackToTheLeastSpecificWording() {
        val later = assertNotNull(contract("""{"address":"0x1234567890AbcdEF1234567890aBcdef12345678","role":"delegate","known":false}"""))
        assertEquals(ReviewContract.Role.Contract, later.role)
        assertEquals(ContractNoticeKind.Unlisted, later.noticeKind)
    }

    @Test
    fun aRecordWithoutAnAddressIsNoRecord() {
        assertNull(contract("""{"role":"spender","known":true,"name":"X"}"""))
        assertNull(contract("""{"address":"","known":false}"""))
        assertNull(contract("""{"address":"   ","known":false}"""))
        assertNull(ReviewContract.parse(null))
        assertNull(ReviewContract.parse(JsonPrimitive("0xabc")))
        assertNull(contract("""{"address":"${"a".repeat(400)}","known":false}"""))
    }

    @Test
    fun theReviewReadsTheContractTheEngineAttached() {
        val r = review(
            """{"chain":"evm","title":"Approve token spending","summary":"Approve unlimited USDT",
            "fields":[{"label":"Spender","value":"0x000000000022D473030F116dDEE9F6B43aC78BA3"}],
            "warnings":[],"requestHash":"ab","riskLevel":"high","decodeStatus":"fullyDecoded",
            "display":{"action":"Approve Spending","counterpartyLabel":"Spender","counterparty":"0x000000000022D473030F116dDEE9F6B43aC78BA3"},
            "contract":{"address":"0x000000000022D473030F116dDEE9F6B43aC78BA3","role":"spender","known":true,"name":"Uniswap Permit2","protocol":"Uniswap","category":"infra","everyChain":true}}""",
        )
        assertEquals("Uniswap Permit2", r.contract?.name)
        assertTrue(ReviewPresentation.showsNoticeInSummary(r))
        assertFalse(ReviewPresentation.showsNoticeCard(r))
    }

    @Test
    fun aNoticeAboutAnAddressTheSummaryDoesNotShowGetsItsOwnCard() {
        val r = review(
            """{"chain":"evm","title":"Token transfer","summary":"Send 1 USDT","fields":[],"warnings":[],"requestHash":"ab","decodeStatus":"fullyDecoded",
            "display":{"action":"Send","counterpartyLabel":"To","counterparty":"0xBAbAbabAbabaBABaBAbABabaBAbAbaBaBAbABaBa"},
            "contract":{"address":"0xdAC17F958D2ee523a2206206994597C13D831ec7","role":"contract","known":false,"token":"Tether USD (USDT)"}}""",
        )
        assertEquals("Tether USD (USDT)", r.contract?.token)
        assertFalse(ReviewPresentation.showsNoticeInSummary(r))
        assertTrue(ReviewPresentation.showsNoticeCard(r))
    }

    @Test
    fun aChecksumDifferenceStillCountsAsTheSameAddress() {
        val r = review(
            """{"chain":"evm","title":"Approve","summary":"","fields":[],"warnings":[],"requestHash":"ab",
            "display":{"action":"Approve Spending","counterpartyLabel":"Spender","counterparty":"0x000000000022D473030F116dDEE9F6B43aC78BA3"},
            "contract":{"address":"0x000000000022d473030f116ddee9f6b43ac78ba3","role":"spender","known":true,"name":"Uniswap Permit2"}}""",
        )
        assertTrue(ReviewPresentation.showsNoticeInSummary(r))
    }

    @Test
    fun aReviewWithoutAContractHasNoNotice() {
        val r = review("""{"chain":"bitcoin","title":"Send bitcoin","summary":"","fields":[],"warnings":[],"requestHash":"ab"}""")
        assertNull(r.contract)
        assertFalse(ReviewPresentation.showsNoticeInSummary(r))
        assertFalse(ReviewPresentation.showsNoticeCard(r))
    }

    @Test
    fun addressGroupingPreservesEveryCharacter() {
        val evm = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
        val groups = AddressFormatter.groups(evm)
        assertEquals("0x9858", groups.first())
        assertEquals(evm, groups.joinToString(""))
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", AddressFormatter.groups("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu").joinToString(""))
        assertEquals("0x9858Ef…da94", AddressFormatter.short(evm))
        assertTrue(AddressFormatter.looksLikeAddress(evm))
        assertFalse(AddressFormatter.looksLikeAddress("Personal signature (EIP-191)"))
        assertEquals(listOf(""), AddressFormatter.groups(""))
        assertEquals("0x000000…C78BA3", AddressFormatter.short("0x000000000022D473030F116dDEE9F6B43aC78BA3", 6, 6))
        assertEquals("abc", AddressFormatter.short("abc"))
        assertEquals("TUEZSd…GWYdH", AddressFormatter.short("TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH", 6, 5))
        assertEquals("0X1234", AddressFormatter.groups("0X12345678").first())
        // Only a lowercase "0x" widens the kept prefix (iOS `hasPrefix("0x")`).
        assertEquals("0X1234…2345", AddressFormatter.short("0X123456789012345"))
    }

    @Test
    fun reviewDecodesEngineDisplayAndNetwork() {
        val r = review(
            """{"chain":"evm","title":"Native transfer","summary":"1 ETH","requestHash":"${"a".repeat(64)}",
            "fields":[{"label":"Network","value":"Base"}],"warnings":[],"decodeStatus":"fullyDecoded","riskLevel":"normal",
            "network":{"chainId":"8453","name":"Base","symbol":"ETH"},
            "display":{"action":"Send","amount":"1","symbol":"ETH","counterpartyLabel":"To","counterparty":"0x0000000000000000000000000000000000000001","fee":"0.0001 ETH"}}""",
        )
        assertEquals(NetworkBrand.Base, r.network?.brand)
        assertEquals("0.0001 ETH", r.display?.fee)
        assertFalse(r.requiresAcknowledgement)
        val risky = review("""{"chain":"evm","decodeStatus":"partiallyDecoded","riskLevel":"warning","requestHash":""}""")
        assertTrue(risky.requiresAcknowledgement)
        val unknown = review("""{"chain":"evm","requestHash":""}""")
        assertTrue(unknown.decodeStatus == DecodeStatus.Opaque && unknown.requiresAcknowledgement)
        val high = review("""{"chain":"evm","decodeStatus":"fullyDecoded","riskLevel":"high","requestHash":""}""")
        assertTrue(high.requiresAcknowledgement)
    }

    @Test
    fun parsingDefaultsFollowIos() {
        val r = review("""{"chain":"nope","fields":[{"label":"Network","value":"Testnet"},{"value":7},"x"],"warnings":["a"]}""")
        assertEquals(Chain.Bitcoin, r.chain)
        assertEquals("Review request", r.title)
        assertTrue(r.fields.isEmpty(), "a fields list with a non-object element is dropped whole")
        assertEquals(RiskLevel.Warning, r.riskLevel)
        val withFields = review("""{"chain":"tron","fields":[{"label":"Network","value":"TRON"},{"value":7}],"warnings":[]}""")
        assertEquals(listOf("Network" to "TRON", "" to "7"), withFields.fields.map { it.label to it.value })
        assertEquals("TRON", withFields.networkName)
        assertEquals(RiskLevel.Normal, withFields.riskLevel)
        assertEquals("Solana", review("""{"chain":"solana"}""").networkName)
        assertEquals(NetworkBrand.Solana, review("""{"chain":"solana","network":{"name":"Solana","symbol":"SOL"}}""").network?.brand)
        assertNull(review("""{"chain":"evm","network":{"chainId":"10","name":"OP Mainnet"}}""").network?.brand)
        assertNull(review("""{"chain":"evm","network":{"chainId":"1"}}""").network)
    }

    @Test
    fun anAssetDecodesStrictlyOrNotAtAll() {
        val ok = """{"chainFamily":"evm","chainId":"1","assetAddress":"0xdac17f958d2ee523a2206206994597c13d831ec7","name":"Tether USD","symbol":"USDT","decimals":6,"icon":"373b13fefbb58245"}"""
        assertEquals(6, ReviewAsset.parse(parseJsonObject(ok))?.decimals)
        assertNull(ReviewAsset.parse(parseJsonObject(ok.replace("\"decimals\":6", "\"decimals\":\"6\""))))
        assertNull(ReviewAsset.parse(parseJsonObject(ok.replace("\"chainId\":\"1\"", "\"chainId\":1"))), "a wrongly typed optional fails the whole asset")
        assertNotNull(ReviewAsset.parse(parseJsonObject(ok.replace("\"chainId\":\"1\"", "\"chainId\":null"))))
        assertNull(ReviewAsset.parse(parseJsonObject(ok.replace(",\"icon\":\"373b13fefbb58245\"", ""))))
    }

    @Test
    fun counterpartyFallsBackToASingleAddressField() {
        val r = review("""{"chain":"tron","fields":[{"label":"Recipient","value":"TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"}]}""")
        assertEquals(ReviewPresentation.Counterparty("To", "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"), ReviewPresentation.counterparty(r))
        val two = review("""{"chain":"tron","fields":[{"label":"Recipient","value":"TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"},{"label":"Instruction 2 · Spender","value":"TTTFe9haCY6CACG9iTM8uyL89pFEPy4ctW"}]}""")
        assertNull(ReviewPresentation.counterparty(two))
        val noValue = review("""{"chain":"evm","display":{"action":"Send"}}""")
        assertNull(ReviewPresentation.counterparty(noValue))
        // "Safe: Spender" is not a bare Spender label (only the " · " group is stripped), so there is no fallback.
        assertNull(ReviewPresentation.counterparty(review("""{"chain":"evm","fields":[{"label":"Safe: Spender","value":"0x9858EfFD232B4033E47d90003D41EC34EcaEda94"}]}""")))
        assertEquals(
            ReviewPresentation.Counterparty("Spender", "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"),
            ReviewPresentation.counterparty(review("""{"chain":"evm","fields":[{"label":"Call 1 · Spender","value":"0x9858EfFD232B4033E47d90003D41EC34EcaEda94"}]}""")),
        )
    }

    @Test
    fun headlinesAmountsWarningsAndFees() {
        val withAmount = review("""{"chain":"evm","summary":"s","display":{"action":"Send","amount":"1","symbol":"ETH","headline":"h","fee":"0.000396834604360128 ETH"}}""")
        assertEquals("1 ETH", ReviewPresentation.amountText(withAmount))
        assertNull(ReviewPresentation.textHeadline(withAmount))
        assertEquals("h", ReviewPresentation.secondaryHeadline(withAmount))
        assertEquals(ReviewPresentation.FeeLine("0.00039684 ETH", energyBudget = false), ReviewPresentation.feeLine(withAmount.display))
        val noDisplay = review("""{"chain":"bitcoin","summary":"Send 0.001 BTC"}""")
        assertEquals("Send 0.001 BTC", ReviewPresentation.textHeadline(noDisplay))
        assertNull(ReviewPresentation.textHeadline(review("""{"chain":"bitcoin","summary":""}""")))
        val tron = review("""{"chain":"tron","display":{"action":"Call","fee":"100.123456789 TRX","feeKind":"energyBudget"}}""")
        assertEquals(ReviewPresentation.FeeLine("100.12345679 TRX", energyBudget = true), ReviewPresentation.feeLine(tron.display))
        assertTrue(ReviewPresentation.isSevereWarning("high risk: delegatecall"))
        assertTrue(ReviewPresentation.isSevereWarning("UNLIMITED spending approval."))
        assertTrue(ReviewPresentation.isSevereWarning("HIGH FEE: 2 ETH"))
        assertFalse(ReviewPresentation.isSevereWarning("Message signatures can authorize logins"))
        // Activity's Signed Request page keeps its own rule (ActivityTab.swift:292): any "HIGH…" prefix, not UNLIMITED.
        assertTrue(ReviewPresentation.isSevereStoredWarning("HIGH FEE: 2 ETH"))
        assertTrue(ReviewPresentation.isSevereStoredWarning("high risk: delegatecall"))
        assertFalse(ReviewPresentation.isSevereStoredWarning("UNLIMITED spending approval."))
        assertTrue(ReviewPresentation.looksTechnical("0xdeadbeef"))
        assertTrue(ReviewPresentation.looksTechnical("m/44'/60'/0'/0/0"))
        assertTrue(ReviewPresentation.looksTechnical("deadbeefdeadbeef"))
        assertTrue(ReviewPresentation.looksTechnical("a\nb"))
        assertFalse(ReviewPresentation.looksTechnical("Ethereum"))
    }

    @Test
    fun theDecodedEssentialsNameTheActionAmountAndCounterparty() {
        val lines = ActivityEssential.of(
            review(
                """{"chain":"evm","title":"Approve token spending","summary":"","fields":[],"warnings":[],"requestHash":"ab",
                "display":{"action":"Approve Spending","amount":"Unlimited","symbol":"USDT","counterpartyLabel":"Spender","counterparty":"0x000000000022D473030F116dDEE9F6B43aC78BA3","fee":"0.0012 ETH"}}""",
            ),
        )
        assertEquals(listOf("action", "amount", "counterparty", "fee"), lines.map { it.id })
        assertEquals("Unlimited USDT", lines[1].value)
        assertEquals("Spender", lines[2].label)
        assertTrue(lines[2].value.startsWith("0x0000") && lines[2].value.contains("…"))
        assertEquals("0.0012 ETH", lines[3].value)
        assertEquals("Network fee", lines[3].label)
        val plain = ActivityEssential.of(review("""{"chain":"bitcoin","title":"Send bitcoin","summary":"","fields":[],"warnings":[],"requestHash":"ab"}"""))
        assertEquals(listOf("Send bitcoin"), plain.map { it.value })
        val energy = ActivityEssential.of(review("""{"chain":"tron","title":"t","display":{"action":"Call","fee":"1.123456789 TRX","feeKind":"energyBudget"}}"""))
        assertEquals(ActivityEssential("fee", "Energy budget", "1.12345679 TRX"), energy.last())
    }
}
