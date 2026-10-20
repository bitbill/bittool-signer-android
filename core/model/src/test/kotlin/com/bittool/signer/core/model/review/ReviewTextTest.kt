package com.bittool.signer.core.model.review

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** Port of `BitToolSignerTests/ReviewTextTests.swift` plus the translation paths with a sample table. */
class ReviewTextTest {
    private val english = ReviewText(EngineTextLookup.None)
    private val chinese = ReviewText { mapOf("Method" to "方法", "Amount" to "金额", "Send" to "发送", "Safe:" to "Safe：").get(it) }

    @Test
    fun anUntranslatedPhraseFallsBackToTheEngineWording() {
        assertEquals("Dilithium Vault Rebalance", english.phrase("Dilithium Vault Rebalance"))
        assertEquals("Entangled amount (QBIT)", english.label("Entangled amount (QBIT)"))
        assertEquals("Warp 0xdeadbeef", english.headline("Warp 0xdeadbeef"))
        assertEquals("", english.phrase(""))
    }

    @Test
    fun aHeadlineKeepsTheValueTheEngineFilledIn() {
        assertTrue(english.headline("Method 0x3593564c").endsWith(" 0x3593564c"))
        assertEquals("Method ", english.headline("Method "))
        assertEquals("方法 0x3593564c", chinese.headline("Method 0x3593564c"))
        assertEquals("Safe： Recipient", chinese.headline("Safe: Recipient"))
    }

    @Test
    fun aLabelTranslatesItsStemAndKeepsTheUnit() {
        assertEquals("金额 (USDC)", chinese.label("Amount (USDC)"))
        assertEquals("金额", chinese.label("Amount"))
        assertEquals("Native amount (wei)", chinese.label("Native amount (wei)"))
        assertEquals("发送", chinese.phrase("Send"))
    }

    @Test
    fun aFeeCeilingIsShownToEightDecimalsAndRoundsUp() {
        assertEquals("0.00039684 ETH", ReviewText.shortDecimal("0.000396834604360128 ETH"))
        assertEquals("1.00000000 ETH", ReviewText.shortDecimal("0.999999999 ETH"))
        assertEquals("1.12345679 ETH", ReviewText.shortDecimal("1.123456789 ETH"))
        assertEquals("0.50000000 ETH", ReviewText.shortDecimal("0.500000000000 ETH"))
    }

    @Test
    fun aFeeTooLargeForAnIntegerStillRenders() {
        assertEquals("9223372036854775808.00000000 ETH", ReviewText.shortDecimal("9223372036854775807.99999999999998 ETH"))
        assertEquals(
            "100000000000000000000000000.00000000 ETH",
            ReviewText.shortDecimal("99999999999999999999999999.999999999 ETH"),
        )
        assertEquals("10.00000000", ReviewText.shortDecimal("9.999999999"))
    }

    @Test
    fun shorteningLeavesAnythingItCannotRoundAlone() {
        assertEquals("0.0001 ETH", ReviewText.shortDecimal("0.0001 ETH"))
        assertEquals("12 TRX", ReviewText.shortDecimal("12 TRX"))
        assertEquals("0.00039683", ReviewText.shortDecimal("0.00039683"))
        assertEquals("about 0.1 ETH", ReviewText.shortDecimal("about 0.1 ETH"))
        assertEquals("", ReviewText.shortDecimal(""))
        assertEquals("1.2.345678901 ETH", ReviewText.shortDecimal("1.2.345678901 ETH"))
    }
}
