package com.bittool.signer.core.model.review

import com.bittool.signer.core.model.AddressFormatter
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.review.ReviewPresentation.DetailRowStyle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** Port of `BitToolSignerTests/NFTReviewDisplayTests.swift` (parsing, summary helpers, Details routing). */
class NftReviewDisplayTest {
    private val contract = "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D"
    private val ensId = "79233663829379634837589865448569342784712482819484549289560981379859480642508"

    private val fixtures: JsonObject = parseJsonObject(
        """
        {
        "ensTransfer": {"action": "Send NFT", "headline": "NFT #79233663…80642508 (77 digits)", "counterpartyLabel": "To", "counterparty": "0xBAbAbabAbabaBABaBAbABabaBAbAbaBaBAbABaBa", "assetKind": "nft", "assetContract": "0x57f1887a8BF19b14fC0dF6Fd9B2acc9Af147eA85", "assetContractLabel": "NFT contract (not verified)", "tokenId": "79233663829379634837589865448569342784712482819484549289560981379859480642508", "fee": "0.0002 ETH"},
        "ambiguousApproveHuge": {"action": "Approve (NFT or token)", "headline": "NFT #57896044…64819968 (77 digits) — or 57896044…64819968 (77 digits) raw token units if this is an ERC-20", "counterpartyLabel": "Spender", "counterparty": "0xBAbAbabAbabaBABaBAbABabaBAbAbaBaBAbABaBa", "assetKind": "nftOrToken", "assetContract": "0x1111111111111111111111111111111111111111", "assetContractLabel": "Contract (NFT or token)", "tokenId": "57896044618658097711785492504343953926634992332820282019728792003956564819968", "fee": "0.0002 ETH"},
        "ambiguousApproveUnlimited": {"action": "Approve (NFT or token)", "amount": "Unlimited", "symbol": "tokens", "headline": "NFT #11579208…29639935 (78 digits) — or unlimited raw token units if this is an ERC-20", "counterpartyLabel": "Spender", "counterparty": "0xBAbAbabAbabaBABaBAbABabaBAbAbaBaBAbABaBa", "assetKind": "nftOrToken", "assetContract": "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D", "assetContractLabel": "Contract (NFT or token)", "tokenId": "115792089237316195423570985008687907853269984665640564039457584007913129639935", "fee": "0.0002 ETH"},
        "ambiguousTransferShort": {"action": "Transfer (NFT or token)", "headline": "NFT #1234 — or 1234 raw units if ERC-20", "counterpartyLabel": "To", "counterparty": "0xBAbAbabAbabaBABaBAbABabaBAbAbaBaBAbABaBa", "assetKind": "nftOrToken", "assetContract": "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D", "assetContractLabel": "Contract (NFT or token)", "tokenId": "1234", "fee": "0.0002 ETH"},
        "erc1155Single": {"action": "Send ERC-1155 Token", "headline": "3 × #7", "counterpartyLabel": "To", "counterparty": "0xBAbAbabAbabaBABaBAbABabaBAbAbaBaBAbABaBa", "assetKind": "multiToken", "assetContract": "0x1111111111111111111111111111111111111111", "assetContractLabel": "ERC-1155 contract (not verified)", "tokenId": "7", "fee": "0.0002 ETH"},
        "erc1155Batch": {"action": "Send ERC-1155 Batch", "headline": "3 items · IDs #7, #8 (+1 more)", "counterpartyLabel": "To", "counterparty": "0xBAbAbabAbabaBABaBAbABabaBAbAbaBaBAbABaBa", "assetKind": "multiToken", "assetContract": "0x1111111111111111111111111111111111111111", "assetContractLabel": "ERC-1155 contract (not verified)", "fee": "0.0002 ETH"},
        "collectionApproval": {"action": "Approve All NFTs", "headline": "All your NFTs in 0xBC4C…f13D", "counterpartyLabel": "Operator", "counterparty": "0xBAbAbabAbabaBABaBAbABabaBAbAbaBaBAbABaBa", "assetKind": "nft", "assetContract": "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D", "assetContractLabel": "NFT contract (not verified)", "fee": "0.0002 ETH"},
        "seaportListing": {"action": "List NFT for Sale (Seaport 1.6)", "amount": "0.95", "symbol": "ETH", "headline": "NFT #1234 · 0xBC4C…f13D", "counterpartyLabel": "Marketplace", "counterparty": "0x0000000000000068F116a894984e2DB1123eB395", "assetKind": "nftOrder", "assetContract": "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D", "assetContractLabel": "NFT contract (not verified)", "tokenId": "1234", "give": ["NFT #1234 · 0xBC4C…f13D"], "receive": ["0.95 ETH"], "others": ["0.025 ETH → 0x0000…a719 (OpenSea fee recipient)", "0.025 ETH → 0x1234…7890 (unrecognised recipient)"]},
        "seaportRawPrice": {"action": "List NFT for Sale (Seaport 1.6)", "amount": "100000000000000000000000000000000000000000000000000000000000000000000000000000", "symbol": "raw units", "headline": "NFT #1234 · 0xBC4C…f13D", "counterpartyLabel": "Marketplace", "counterparty": "0x0000000000000068F116a894984e2DB1123eB395", "assetKind": "nftOrder", "assetContract": "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D", "assetContractLabel": "NFT contract (not verified)", "tokenId": "1234", "give": ["NFT #1234 · 0xBC4C…f13D"], "receive": ["100000000000000000000000000000000000000000000000000000000000000000000000000000 raw units of 0x7777…7777"]},
        "seaportMixed": {"action": "Sign Seaport Order (Seaport 1.6)", "headline": "Give 2 items · receive 1 item", "counterpartyLabel": "Marketplace", "counterparty": "0x0000000000000068F116a894984e2DB1123eB395", "assetKind": "nftOrder", "assetContract": "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D", "assetContractLabel": "NFT contract (not verified)", "tokenId": "2", "give": ["NFT #2 · 0xBC4C…f13D", "0.1 ETH"], "receive": ["1 ETH"]}
        }
        """.trimIndent(),
    )!!

    private fun fixture(name: String): JsonObject = fixtures[name] as JsonObject

    private fun review(display: JsonObject?): TransactionReview = TransactionReview.parse(
        buildJsonObject {
            put("chain", "evm"); put("title", "Contract call"); put("summary", ""); put("requestHash", "b".repeat(64))
            put("fields", JsonArray(listOf(buildJsonObject { put("label", "Network"); put("value", "Ethereum") })))
            put("warnings", JsonArray(emptyList())); put("decodeStatus", "fullyDecoded"); put("riskLevel", "normal")
            put("network", buildJsonObject { put("chainId", "1"); put("name", "Ethereum"); put("symbol", "ETH") })
            if (display != null) put("display", display)
        },
    )

    private fun engineDisplay(name: String): ReviewDisplay = assertNotNull(review(fixture(name)).display, name)

    private fun parse(json: String): ReviewDisplay? = ReviewDisplay.parse(parseJsonObject(json))

    @Test
    fun engineFixturesParseWithTheirKind() {
        assertEquals(10, fixtures.size)
        val kinds = mapOf(
            "ensTransfer" to ReviewAssetKind.Nft, "collectionApproval" to ReviewAssetKind.Nft,
            "ambiguousApproveHuge" to ReviewAssetKind.NftOrToken, "ambiguousApproveUnlimited" to ReviewAssetKind.NftOrToken,
            "ambiguousTransferShort" to ReviewAssetKind.NftOrToken, "erc1155Single" to ReviewAssetKind.MultiToken,
            "erc1155Batch" to ReviewAssetKind.MultiToken, "seaportListing" to ReviewAssetKind.NftOrder,
            "seaportRawPrice" to ReviewAssetKind.NftOrder, "seaportMixed" to ReviewAssetKind.NftOrder,
        )
        for ((name, kind) in kinds) {
            val display = engineDisplay(name)
            assertEquals(kind, display.assetKind, name)
            assertTrue(display.assetContract != null && display.assetContractLabel != null, name)
            assertNull(display.note, name)
        }
        val listing = engineDisplay("seaportListing")
        assertEquals(listOf(ReviewFlowSection.Kind.Give, ReviewFlowSection.Kind.Receive, ReviewFlowSection.Kind.Others), listing.flowSections.map { it.kind })
        assertEquals("0.025 ETH → 0x1234…7890 (unrecognised recipient)", listing.others.last())
    }

    @Test
    fun parsesEveryNftField() {
        val display = assertNotNull(
            review(
                parseJsonObject(
                    """{"action":"Send NFT","headline":"NFT #1234","counterpartyLabel":"To","counterparty":"0x2222222222222222222222222222222222222222",
                    "assetKind":"nft","assetContract":"$contract","assetContractLabel":"Collection contract","tokenId":"1234",
                    "give":["NFT #1234 · 0xBC4C…f13D"],"receive":["0.95 ETH"],"others":["0.025 ETH → 0x0000…a719 (OpenSea fee recipient)"]}""",
                ),
            ).display,
        )
        assertEquals("Send NFT", display.action)
        assertTrue(display.assetKind == ReviewAssetKind.Nft && display.isNFT)
        assertEquals(contract, display.assetContract)
        assertEquals("Collection contract", display.assetContractLabel)
        assertEquals("Collection contract", display.assetContractCaption)
        assertEquals("1234", display.tokenId)
        assertEquals(listOf("NFT #1234 · 0xBC4C…f13D"), display.give)
        assertEquals(listOf("0.95 ETH"), display.receive)
        assertEquals(listOf("0.025 ETH → 0x0000…a719 (OpenSea fee recipient)"), display.others)
        assertEquals("0x2222222222222222222222222222222222222222", display.counterparty)
    }

    @Test
    fun parsesEveryAssetKind() {
        for ((raw, kind) in listOf("nft" to ReviewAssetKind.Nft, "nftOrToken" to ReviewAssetKind.NftOrToken, "multiToken" to ReviewAssetKind.MultiToken, "nftOrder" to ReviewAssetKind.NftOrder)) {
            assertEquals(kind, parse("""{"action":"A","assetKind":"$raw"}""")?.assetKind)
        }
    }

    @Test
    fun displayWithoutNftKeysIsUnchanged() {
        val display = assertNotNull(parse("""{"action":"Send","amount":"1","symbol":"ETH","counterpartyLabel":"To","counterparty":"0x0000000000000000000000000000000000000001","fee":"0.0001 ETH"}"""))
        assertEquals(ReviewDisplay(action = "Send", amount = "1", symbol = "ETH", counterpartyLabel = "To", counterparty = "0x0000000000000000000000000000000000000001", fee = "0.0001 ETH"), display)
        assertFalse(display.isNFT)
        assertTrue(display.flowSections.isEmpty())
        assertNull(display.summaryTokenId)
        assertFalse(display.showsAssetBox)
        assertEquals("NFT contract (not verified)", display.assetContractCaption)
    }

    @Test
    fun missingOrMalformedDisplayIsNil() {
        assertNull(ReviewDisplay.parse(null))
        assertNull(ReviewDisplay.parse(JsonPrimitive("Send NFT")))
        assertNull(parse("""{"headline":"NFT #1"}"""))
        assertNull(parse("""{"action":7}"""))
        assertNull(review(parseJsonObject("""{"headline":"NFT #1","assetKind":"nft"}""")).display)
    }

    @Test
    fun wrongTypesAreIgnored() {
        val display = assertNotNull(parse("""{"action":"Send NFT","assetKind":"nftOrder","assetContract":42,"assetContractLabel":["Mint"],"tokenId":1234,"give":"NFT #1","receive":["0.95 ETH",3],"others":{"to":"x"}}"""))
        assertEquals("Send NFT", display.action)
        assertEquals(ReviewAssetKind.NftOrder, display.assetKind)
        assertTrue(display.assetContract == null && display.assetContractLabel == null && display.tokenId == null)
        assertTrue(display.give.isEmpty() && display.receive.isEmpty() && display.others.isEmpty())
        assertNull(parse("""{"action":"Send NFT","assetKind":1}""")?.assetKind)
    }

    @Test
    fun unknownAssetKindDropsEveryNftKey() {
        val base = fixture("seaportListing")
        for (kind in listOf("NFT", "erc721", "", "nftorder", "token", "nftBid")) {
            val raw = JsonObject(base + ("assetKind" to JsonPrimitive(kind)))
            val display = assertNotNull(ReviewDisplay.parse(raw))
            assertTrue(display.assetKind == null && !display.isNFT, kind)
            assertTrue(display.assetContract == null && display.assetContractLabel == null && display.tokenId == null, kind)
            assertTrue(display.give.isEmpty() && display.receive.isEmpty() && display.others.isEmpty(), kind)
            assertTrue(!display.showsAssetBox && display.flowSections.isEmpty() && display.summaryTokenId == null, kind)
            assertTrue(display.action == "List NFT for Sale (Seaport 1.6)" && display.amount == "0.95", kind)
        }
        val withoutKind = assertNotNull(ReviewDisplay.parse(JsonObject(base - "assetKind")))
        assertTrue(!withoutKind.showsAssetBox && withoutKind.flowSections.isEmpty())
        val bare = ReviewDisplay(action = "Send", assetContract = contract, tokenId = ensId, give = listOf("NFT #1"))
        assertTrue(!bare.showsAssetBox && bare.flowSections.isEmpty() && bare.summaryTokenId == null)
    }

    @Test
    fun emptyValuesAreAbsent() {
        val display = assertNotNull(parse("""{"action":"Send NFT","assetKind":"nftOrder","assetContract":"","assetContractLabel":"  ","tokenId":"\n","give":[""," ","NFT #1"],"receive":[]}"""))
        assertTrue(display.assetContract == null && display.assetContractLabel == null && display.tokenId == null)
        assertEquals(listOf("NFT #1"), display.give)
        assertTrue(display.receive.isEmpty())
    }

    @Test
    fun oversizedValuesAreBounded() {
        val long = "9".repeat(301)
        val exact = "7".repeat(300)
        val raw = buildJsonObject {
            put("action", "List NFT"); put("assetKind", "nftOrder"); put("assetContract", long); put("assetContractLabel", long); put("tokenId", exact)
            put("give", JsonArray((1..40).map { JsonPrimitive("NFT #$it") }))
            put("receive", JsonArray((1..32).map { JsonPrimitive("$it ETH") }))
            put("others", JsonArray(listOf(JsonPrimitive(long))))
        }
        val display = assertNotNull(ReviewDisplay.parse(raw))
        assertNull(display.assetContract)
        assertNull(display.assetContractLabel)
        assertEquals(exact, display.tokenId)
        assertEquals(ReviewDisplay.MAX_LIST_LINES, display.give.size)
        assertTrue(display.give.first() == "NFT #1" && display.give[30] == "NFT #31")
        assertEquals("+9 more (see Details)", display.give.last())
        assertEquals((1..32).map { "$it ETH" }, display.receive)
        val line = display.others.first()
        assertEquals(ReviewDisplay.MAX_STRING_LENGTH, line.length)
        assertTrue(line.startsWith("999") && line.endsWith("999") && line.contains("…"))
    }

    @Test
    fun overLongLinesKeepTheRecipientTag() {
        val tail = " → 0x1234…5678 (unrecognised recipient)"
        val line = "Order 3: " + "1".repeat(78) + " raw units of 0x7777…7777 → " + "2".repeat(78) +
            " raw units of 0x7777…7777 (changes linearly over time) " + "x".repeat(80) + tail
        assertTrue(line.length > ReviewDisplay.MAX_STRING_LENGTH)
        val bounded = ReviewDisplay.boundedLine(line)
        assertEquals(ReviewDisplay.MAX_STRING_LENGTH, bounded.length)
        assertTrue(bounded.startsWith("Order 3: " + "1".repeat(78) + " raw units"))
        assertTrue(bounded.endsWith(tail))
        assertTrue(bounded.contains("…"))
        val short = "0.025 ETH$tail"
        assertEquals(short, ReviewDisplay.boundedLine(short))
        assertEquals(listOf(bounded), ReviewDisplay.boundedLines(JsonArray(listOf(JsonPrimitive(line)))))
    }

    @Test
    fun tokenIdRowAndCaptionForEngineDisplays() {
        data class Row(val id: String?, val ambiguous: Boolean, val box: Boolean)
        fun row(name: String) = engineDisplay(name).let { Row(it.summaryTokenId, it.tokenIdIsAmbiguous, it.showsAssetBox) }
        assertEquals(Row(ensId, false, true), row("ensTransfer"))
        val huge = "57896044618658097711785492504343953926634992332820282019728792003956564819968"
        assertEquals(Row(huge, true, true), row("ambiguousApproveHuge"))
        val max = "115792089237316195423570985008687907853269984665640564039457584007913129639935"
        assertEquals(Row(max, true, true), row("ambiguousApproveUnlimited"))
        assertEquals(Row(null, true, true), row("ambiguousTransferShort"))
        assertEquals(Row(null, false, true), row("erc1155Single"))
        assertEquals(Row(null, false, true), row("erc1155Batch"))
        assertEquals(Row(null, false, true), row("seaportListing"))
        assertEquals(Row(null, false, true), row("collectionApproval"))
        assertEquals(Row("2", false, true), row("seaportMixed"))
        assertEquals("NFT contract (not verified)", engineDisplay("ensTransfer").assetContractCaption)
        assertEquals("Contract (NFT or token)", engineDisplay("ambiguousApproveHuge").assetContractCaption)
    }

    @Test
    fun tokenIdRowOnlyWhenTheHeadlineShowsHashId() {
        fun summary(headline: String?, tokenId: String?, amount: String? = null) =
            ReviewDisplay(action = "Send NFT", amount = amount, headline = headline, assetKind = ReviewAssetKind.Nft, tokenId = tokenId).summaryTokenId
        assertNull(summary("NFT #1234", "1234"))
        assertNull(summary("3 × #7", "7"))
        assertNull(summary("NFT #0", "0"))
        assertEquals(ensId, summary("NFT #79233663…80642508 (77 digits)", ensId))
        assertEquals("1234", summary("NFT #12345", "1234"))
        assertEquals("1234", summary("NFT #91234", "1234"))
        assertEquals("1234", summary(null, "1234"))
        assertNull(summary("NFT #1234", null))
        assertNull(summary(null, "88", amount = "Token #88"))
        assertEquals("2", summary("Give 2 items · receive 1 item", "2"))
        assertEquals("1", summary("Give 2 items · receive 1 item", "1"))
        assertEquals("95", summary("NFT #1234 · 0xBC4C…f13D", "95", amount = "0.95"))
        assertEquals("1234", summary("Sale 1234", "1234"))
    }

    @Test
    fun hashIdMatchingRespectsDigitBoundaries() {
        assertTrue(ReviewDisplay.text("NFT #1234", showsTokenId = "1234"))
        assertTrue(ReviewDisplay.text("NFT #1234 — or 1234 raw units if ERC-20", showsTokenId = "1234"))
        assertTrue(ReviewDisplay.text("IDs #12345, #1234 (+1 more)", showsTokenId = "1234"))
        assertTrue(ReviewDisplay.text("3 × #7", showsTokenId = "7"))
        assertFalse(ReviewDisplay.text("1234", showsTokenId = "1234"))
        assertFalse(ReviewDisplay.text("NFT #12345", showsTokenId = "1234"))
        assertFalse(ReviewDisplay.text("NFT #01234", showsTokenId = "1234"))
        assertFalse(ReviewDisplay.text("0.95", showsTokenId = "95"))
        assertFalse(ReviewDisplay.text("Give 2 items", showsTokenId = "2"))
        assertFalse(ReviewDisplay.text("NFT #1234", showsTokenId = ""))
    }

    @Test
    fun nftAmountsAndHeadlinesAreNeverCutOff() {
        for (name in listOf("seaportRawPrice", "ambiguousApproveUnlimited", "ensTransfer")) {
            val display = engineDisplay(name)
            assertNull(ReviewPresentation.amountLineLimit(display), name)
            assertNull(ReviewPresentation.headlineLineLimit(display), name)
        }
        val plain = ReviewDisplay(action = "Send", amount = "1", symbol = "ETH")
        assertEquals(2, ReviewPresentation.amountLineLimit(plain))
        assertEquals(8, ReviewPresentation.headlineLineLimit(plain))
        assertEquals(2, ReviewPresentation.amountLineLimit(null))
        assertEquals(8, ReviewPresentation.headlineLineLimit(null))
    }

    @Test
    fun flowSectionsKeepOrderAndSkipEmptyLists() {
        val display = ReviewDisplay(
            action = "List NFT", amount = "0.95", symbol = "ETH", assetKind = ReviewAssetKind.NftOrder,
            give = listOf("NFT #1234 · 0xBC4C…f13D"), others = listOf("0.025 ETH → 0x0000…a719 (OpenSea fee recipient)"),
        )
        assertEquals(listOf(ReviewFlowSection.Kind.Give, ReviewFlowSection.Kind.Others), display.flowSections.map { it.kind })
        assertEquals("receive", ReviewFlowSection(ReviewFlowSection.Kind.Receive, listOf("1 ETH")).id)
    }

    @Test
    fun spokenTokenIds() {
        assertEquals("1234", ReviewDisplay.spokenTokenId("1234"))
        assertEquals("1234, 5678, 9012, 34", ReviewDisplay.spokenTokenId("12345678901234"))
    }

    @Test
    fun tokenIdsAndQuantitiesAreNeverStyledAsAddresses() {
        val bitcoinLike = "106686051536470969934437590839493823286117473395005282216036227418063047698617"
        assertTrue(AddressFormatter.looksLikeAddress(bitcoinLike))
        for (label in listOf("Token ID", "Item 2 · Token ID", "Call 2: Token ID", "NFT token ID", "Amount / NFT token ID", "Allowance / NFT token ID", "Quantity", "Item 1 · Quantity", "Total quantity", "Items", "Distinct token IDs")) {
            val field = ReviewField(0, label, bitcoinLike)
            assertTrue(field.holdsNumber, label)
            assertTrue(field.isTokenIdentifier, label)
            assertFalse(field.rendersAsAddress, label)
            assertEquals(DetailRowStyle.Identifier, ReviewPresentation.rowStyle(field), label)
        }
        for (label in listOf("Token amount (raw units)", "Allowance (raw units)", "Raw amount", "Native amount (wei)")) {
            val field = ReviewField(0, label, bitcoinLike)
            assertTrue(field.holdsNumber && !field.isTokenIdentifier && !field.rendersAsAddress, label)
            assertEquals(DetailRowStyle.Text, ReviewPresentation.rowStyle(field), label)
        }
    }

    @Test
    fun detailsRowStyles() {
        val items = ReviewField(1, "Items", (1..8).joinToString("\n") { "#${it}000000000000000000000000000 × $it" })
        assertTrue(items.isLong)
        assertEquals(DetailRowStyle.Identifier, ReviewPresentation.rowStyle(items))
        assertEquals(DetailRowStyle.Collapsible, ReviewPresentation.rowStyle(ReviewField(2, "Items", "#1 × 1\n".repeat(700))))
        assertEquals(DetailRowStyle.Collapsible, ReviewPresentation.rowStyle(ReviewField(3, "Signed values", "a".repeat(300))))
        assertEquals(DetailRowStyle.Text, ReviewPresentation.rowStyle(ReviewField(4, "Method", "safeTransferFrom")))
        assertEquals(DetailRowStyle.Address, ReviewPresentation.rowStyle(ReviewField(5, "Recipient", "0x9858EfFD232B4033E47d90003D41EC34EcaEda94")))
        assertEquals("a".repeat(280) + "…", ReviewPresentation.collapsedValue("a".repeat(300)))
    }

    @Test
    fun allDigitAddressesKeepAddressFormatting() {
        val system = ReviewField(0, "Instruction 1 · Program", "1".repeat(32))
        assertTrue(!system.holdsNumber && system.rendersAsAddress)
        assertEquals(DetailRowStyle.Address, ReviewPresentation.rowStyle(system))
        for (label in listOf("Recipient", "Recipient / contract", "Contract", "Mint", "Operator", "Safe: Recipient", "Token account")) {
            val field = ReviewField(0, label, "0x9858EfFD232B4033E47d90003D41EC34EcaEda94")
            assertTrue(!field.holdsNumber && field.rendersAsAddress, label)
        }
        assertFalse(ReviewField(0, "Method", "safeTransferFrom").rendersAsAddress)
    }
}
