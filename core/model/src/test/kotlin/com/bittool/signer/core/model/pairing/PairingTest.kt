package com.bittool.signer.core.model.pairing

import com.bittool.signer.core.model.AccountProfiles
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.ConnectOptions
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletLevelPairing
import com.bittool.signer.core.model.json.parseJsonObject
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Port of `QRPayloadViewTests.swift` plus the pairing payload and Connect option rules (wallets-pairing.md §2, §7). */
class PairingTest {
    private fun obj(json: String) = parseJsonObject(json)!!

    @Test
    fun jsonPayloadIsPrettySortedAndNamedAfterItsTransport() {
        val view = assertNotNull(QrPayloadView.parse(obj("""{"type":"eth-signature","frames":["UR:ETH-SIGNATURE/X"],"payloadFormat":"json","payload":{"signature":"ab","request-id":{"uuid":"01"}},"payloadBytes":89}""")))
        assertEquals("Payload (JSON)", view.titleEnglish)
        assertEquals("ur:eth-signature · CBOR · 89 bytes", view.summary())
        assertEquals("{\n  \"request-id\" : {\n    \"uuid\" : \"01\"\n  },\n  \"signature\" : \"ab\"\n}", view.text)
        assertFalse(view.isPsbt)
    }

    @Test
    fun plainTextAndPsbtTransportsAreLabelled() {
        val address = assertNotNull(QrPayloadView.parse(obj("""{"type":"address","frames":["TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7"],"payloadFormat":"text","payload":"TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7","payloadBytes":34}""")))
        assertEquals("Payload (Text)", address.titleEnglish)
        assertEquals("Plain text · 34 bytes", address.summary())
        val bbqr = assertNotNull(QrPayloadView.parse(obj("""{"type":"bbqr-psbt","frames":["B${'$'}HP0100abcd"],"payloadFormat":"json","payload":{"psbt_base64":"cHNidP8="},"payloadBytes":379}""")))
        assertEquals("BBQr · PSBT (hex) · 379 bytes", bbqr.summary())
        assertTrue(bbqr.isPsbt)
        val base32 = assertNotNull(QrPayloadView.parse(obj("""{"type":"bbqr-psbt","frames":["B${'$'}2P0100abcd"],"payloadFormat":"json","payload":{"psbt_base64":"cHNidP8="},"payloadBytes":1379}""")))
        assertEquals("BBQr · PSBT (Base32) · 1,379 bytes", base32.summary(Locale.US))
        val hex = assertNotNull(QrPayloadView.parse(obj("""{"type":"bytes","frames":["UR:BYTES/X"],"payloadFormat":"hex","payload":"0102","payloadBytes":3}""")))
        assertEquals("Payload (Hex)", hex.titleEnglish)
        val noBytes = assertNotNull(QrPayloadView.parse(obj("""{"type":"psbt-base64","payloadFormat":"text","payload":"cHNidP8="}""")))
        assertEquals(8L, noBytes.byteCount)
        assertEquals("Plain text · PSBT Base64 · 8 bytes", noBytes.summary())
    }

    @Test
    fun missingOrInvalidPayloadHidesTheDisclosure() {
        assertNull(QrPayloadView.parse(obj("""{"frames":["x"]}""")))
        assertNull(QrPayloadView.parse(obj("""{"payloadFormat":"yaml","payload":"x"}""")))
        assertNull(QrPayloadView.parse(obj("""{"payloadFormat":"json","payload":"not an object"}""")))
        assertNull(QrPayloadView.parse(obj("""{"payloadFormat":"text","payload":""}""")))
    }

    @Test
    fun fingerprintNoteNamesTheDecimalForm() {
        val view = assertNotNull(QrPayloadView.parse(obj("""{"type":"crypto-account","payloadFormat":"json","payload":{"master-fingerprint":1942346250}}""")))
        assertEquals(listOf("Fingerprints are stored as 32-bit numbers: 1942346250 is fingerprint 73C5DA0A."), QrPayloadView.fingerprintNotes(view, "73c5da0a"))
        assertTrue(QrPayloadView.fingerprintNotes(view, "ffffffff").isEmpty())
        assertTrue(QrPayloadView.fingerprintNotes(null, "73c5da0a").isEmpty())
    }

    @Test
    fun coinLevelPayloadRequiresFrames() {
        val error = assertFailsWith<AppIssue> { PairingPayload.parse(obj("""{"frames":[]}""")) }
        assertEquals(UiText.Ios("Unable to encode public wallet information."), error.text)
        val payload = PairingPayload.parse(obj("""{"frames":["A","B"],"address":"bc1q","path":"m/84'/0'/0'","outputMode":"accountXpub","note":"n"}"""))
        assertEquals("A\nB", payload.text)
        assertEquals("bc1q", payload.address)
        assertNull(payload.payloadView)
    }

    @Test
    fun walletLevelPayloadKeepsVerifyRowsAndPrettyJson() {
        assertFailsWith<AppIssue> { WalletPairingPayload.parse(obj("""{"frames":["A"],"payload":"x"}""")) }
        val payload = WalletPairingPayload.parse(
            obj(
                """{"frames":["UR:BYTES/1-2/A","UR:BYTES/2-2/B"],"mode":"full","payloadBytes":1561,"payload":{"version":1,"protocol":"bittool-signer-wallet","url":"a/b"},
                "verify":[{"chain":"btc","profile":"native","path":"m/84'/0'/0'/0/0","address":"bc1q"},{"chain":"evm","path":"m/44'/60'/0'/0/0","address":"0x"},{"chain":"sol","profile":"trust"}]}""",
            ),
        )
        assertEquals(listOf("btc/native", "evm/standard"), payload.firstAddresses.map { it.id })
        assertEquals("UR:BYTES/1-2/A\nUR:BYTES/2-2/B", payload.text)
        assertEquals("{\n  \"protocol\" : \"bittool-signer-wallet\",\n  \"url\" : \"a/b\",\n  \"version\" : 1\n}", payload.json)
        assertEquals("bittool-signer-wallet v1 · ur:bytes (canonical CBOR) · 1561 bytes", payload.formatLine)
    }

    @Test
    fun connectOptionsDecideTheExactPairingParameters() {
        val btc = ConnectOptions()
        assertEquals("bitcoin|native|accountXpub|0", btc.requestKey)
        assertEquals(listOf("accountXpub", "address"), btc.exportModes.map { it.wire })
        val multisig = btc.copy(multisig = true, btcMode = "address")
        assertEquals("multisig-native", multisig.profile)
        assertEquals("accountXpub", multisig.outputMode)
        assertTrue(multisig.exportModes.isEmpty())
        val evm = btc.copy(chain = Chain.Evm, tronIndex = 5)
        assertEquals("evm|standard|wallet|0", evm.requestKey)
        assertEquals(listOf("Wallet", "Address"), evm.exportModes.map { it.title })
        val tron = btc.copy(chain = Chain.Tron, tronIndex = AccountProfiles.MAX_TRON_INDEX)
        assertEquals("tron|standard|address|2147483647", tron.requestKey)
        assertEquals("m/44'/195'/0'/0/2147483647", tron.tronPath)
        assertTrue(tron.exportModes.isEmpty())
        val sol = btc.copy(chain = Chain.Solana, solanaProfile = "legacy", solanaMode = "address")
        assertEquals("solana|legacy|address|0", sol.requestKey)
    }

    @Test
    fun walletLevelRowsNameTheirAccounts() {
        assertEquals("Bitcoin · Taproot", WalletLevelPairing.rowName("btc", "taproot"))
        assertEquals("Solana · Trust", WalletLevelPairing.rowName("sol", "trust"))
        assertEquals("Ethereum & EVM", WalletLevelPairing.rowName("evm", "standard"))
        assertEquals("TRON", WalletLevelPairing.rowName("tron", "standard"))
        assertNull(WalletLevelPairing.rowName("doge", "standard"))
        assertEquals("Standard", AccountProfiles.solanaPreset("m/44'/501'/0'/0'")?.title)
    }
}
