package com.bittool.signer.feature.activity

import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.model.signing.SigningReceipt
import java.time.LocalDateTime
import java.time.ZoneOffset
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

/** Public, non-secret fixtures for Activity tests (the published BIP39 test wallet's fingerprint, made-up hashes). */
object ActivityTestData {
    val walletId: WalletId = WalletId.parse("6f1c2a5e-4b3d-4c2a-9e1f-0a1b2c3d4e5f")!!
    val wallet = VaultMetadata(walletId, "Public fixture 1", 1_760_000_000_000L)

    const val HASH = "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12"
    const val REQUEST_JSON = """{"chain":"evm","payload":"ur:eth-sign-request/oe"}"""
    const val SPENDER = "0x000000000022D473030F116dDEE9F6B43aC78BA3"

    /** 2026-10-20 at [hour]:[minute] UTC, in epoch milliseconds. */
    fun utc(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    fun receipt(
        id: String,
        date: Long = utc(20, 9),
        title: String = "Approve token spending",
        summary: String? = "Unlimited USDT to Permit2",
        walletId: WalletId? = this.walletId,
        resultType: String? = SigningReceipt.RESULT_SIGNATURE,
        riskLevel: String? = "normal",
        requestJson: String? = REQUEST_JSON,
        frames: List<String>? = listOf("UR:ETH-SIGNATURE/OEADTPDAGDNDCAWMGTFRKIGRPMNDUTDNBTKGFSSBJNAOHDCX"),
        text: String? = "ur:eth-signature/oeadtpdagdndcawmgtfrkigrpmndutdnbtkgfssbjnaohdcx",
        masterFingerprint: String? = "73c5da0a",
        chain: Chain = Chain.Evm,
        chainId: String? = "1",
    ) = SigningReceipt(
        id = id,
        date = date,
        chain = chain,
        title = title,
        requestHash = HASH,
        walletId = walletId?.toString(),
        masterFingerprint = masterFingerprint,
        resultType = resultType,
        summary = summary,
        networkName = "Ethereum",
        chainId = chainId,
        riskLevel = riskLevel,
        requestJson = requestJson,
        responseFrames = frames,
        responseText = text,
    )

    /** The engine review of the iOS `theDecodedEssentialsNameTheActionAmountAndCounterparty` case. */
    val approvalReview: TransactionReview = TransactionReview.parse(
        parseJsonObject(
            """
            {"chain": "evm", "title": "Approve token spending", "summary": "", "fields": [{"label": "Spender", "value": "$SPENDER"}],
             "warnings": ["UNLIMITED approval"], "requestHash": "$HASH",
             "display": {"action": "Approve Spending", "amount": "Unlimited", "symbol": "USDT",
             "counterpartyLabel": "Spender", "counterparty": "$SPENDER", "fee": "0.0012 ETH"}}
            """.trimIndent(),
        )!!,
    )

    /** A request with no structured display: it still names what it was. */
    val plainReview: TransactionReview = TransactionReview.parse(
        parseJsonObject("""{"chain": "bitcoin", "title": "Send bitcoin", "summary": "", "fields": [{"label": "Network", "value": "Bitcoin"}], "warnings": [], "requestHash": "$HASH"}""")!!,
    )
}
