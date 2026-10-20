package com.bittool.signer.core.ui.text

import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.model.AccountProfiles
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.ExportModeChoice
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.pairing.QrPayloadView
import com.bittool.signer.core.model.review.ReviewText
import com.bittool.signer.core.model.review.EngineTextLookup
import com.bittool.signer.core.ui.catalog.CatalogSamples
import com.bittool.signer.core.ui.legal.boldSpans
import com.bittool.signer.core.ui.logo.tokenLetters
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Draw-time strings of `:core:ui` (DECISIONS §4, ARCHITECTURE §4.3 G6 rows). */
@RunWith(RobolectricTestRunner::class)
class TextsTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    private fun view(type: String?, bytes: Long, encoding: String? = null) =
        QrPayloadView(QrPayloadView.Format.Json, "{}", bytes, type, encoding)

    @Test
    fun payloadSummariesKeepProtocolTokensAndGroupTheByteCount() {
        assertEquals("ur:crypto-account · CBOR · 1,204 bytes", payloadSummary(view("crypto-account", 1_204), resources, Locale.US))
        assertEquals("Plain text · 170 bytes", payloadSummary(view(null, 170), resources, Locale.US))
        assertEquals("Plain text · PSBT Base64 · 90 bytes", payloadSummary(view("psbt-base64", 90), resources, Locale.US))
        assertEquals("BBQr · PSBT (hex) · 9 bytes", payloadSummary(view("bbqr-psbt", 9, "H"), resources, Locale.US))
        assertEquals("BBQr · PSBT (Base32) · 9 bytes", payloadSummary(view("bbqr-psbt", 9, "2"), resources, Locale.US))
        // Same as the model's English summary in English.
        val sample = view("crypto-hdkey", 2_048)
        assertEquals(sample.summary(Locale.US), payloadSummary(sample, resources, Locale.US))
    }

    @Test
    fun exportModesAndProfilesMapToResourcesAndUnknownOnesStayVerbatim() {
        compose.setContent {
            Column {
                Text(ExportModeChoice("accountXpub", "Account XPUB").localizedTitle())
                Text(ExportModeChoice("address", "Address").localizedTitle())
                Text(ExportModeChoice("future", "Future mode").localizedTitle())
                Text(AccountProfiles.solana[1].localizedTitle())
            }
        }
        listOf("Account XPUB", "Address", "Future mode", "Trust").forEach { compose.onNodeWithText(it).assertExists() }
    }

    @Test
    fun theOverflowLineIsTranslatedAndEngineLinesStayVerbatim() {
        assertEquals("+9 more (see Details)", flowLine("+9 more (see Details)", resources))
        assertEquals("0.95 ETH", flowLine("0.95 ETH", resources))
        assertEquals("+x more (see Details)", flowLine("+x more (see Details)", resources))
    }

    @Test
    fun theDefaultReviewTitleIsAnAndroidResource() {
        val text = ReviewText(EngineTextLookup.None)
        val review = CatalogSamples.review("""{"action": "Send NFT"}""")
        assertEquals("Send NFT", reviewActionLine(review, text, resources))
        val untitled = CatalogSamples.review("null").copy(title = "Review request")
        assertEquals("Review request", reviewActionLine(untitled, text, resources))
    }

    @Test
    fun tokenFallbacksShowThreeUpperCasedCharacters() {
        assertEquals("USD", tokenLetters("usdt"))
        assertEquals("ETH", tokenLetters("ETH"))
        assertEquals("ÉT", tokenLetters("ét"))
    }

    @Test
    fun boldSpansDropTheirMarkers() {
        val text = boldSpans("Keys stay **offline** and **local**.")
        assertEquals("Keys stay offline and local.", text.text)
        assertEquals(2, text.spanStyles.size)
        assertEquals(FontWeight.SemiBold, text.spanStyles[0].item.fontWeight)
        assertEquals("an **unclosed marker", boldSpans("an **unclosed marker").text)
    }

    @Test
    @Config(qualifiers = "b+zh+Hans")
    fun uiTextResolvesInTheCompositionLanguage() {
        compose.setContent {
            Column {
                Text(UiText.Ios("Wallets").asString())
                Text(AccountProfiles.bitcoinSingle[1].localizedTitle())
                Text(Chain.Evm.localizedLongName())
            }
        }
        compose.onNodeWithText("钱包").assertExists()
        // Product names stay untranslated (DECISIONS §10).
        compose.onNodeWithText("Taproot").assertExists()
        compose.onNodeWithText("Ethereum & EVM").assertExists()
    }
}
