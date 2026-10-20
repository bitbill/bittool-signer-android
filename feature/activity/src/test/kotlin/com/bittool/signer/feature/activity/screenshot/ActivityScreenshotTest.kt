package com.bittool.signer.feature.activity.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.Chain
import com.bittool.signer.feature.activity.ActivityContent
import com.bittool.signer.feature.activity.ActivityDay
import com.bittool.signer.feature.activity.ActivityDetailContent
import com.bittool.signer.feature.activity.ActivityDetailUiState
import com.bittool.signer.feature.activity.ActivityEntry
import com.bittool.signer.feature.activity.ActivityTestData
import com.bittool.signer.feature.activity.ActivityTestData.receipt
import com.bittool.signer.feature.activity.ActivityTestData.utc
import com.bittool.signer.feature.activity.ActivityUiState
import com.bittool.signer.feature.activity.DecodeState
import com.bittool.signer.feature.activity.SignedRequestContent
import com.bittool.signer.feature.activity.WalletLabel
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The looks every screen is recorded in (design-system.md §9.14). */
enum class Look(val dark: Boolean, val fontScale: Float, val rtl: Boolean) {
    Light(false, 1f, false),
    Dark(true, 1f, false),
    FontScale2(false, 2f, false),
    Rtl(true, 1f, true),
}

/** The Activity screens recorded for visual QA into `feature/activity/build/outputs/roborazzi/` (record only). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ActivityScreenshotTest(private val look: Look) {
    @get:Rule
    val compose = createComposeRule()

    private val zone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        if (look.rtl) RuntimeEnvironment.setQualifiers("+ar")
    }

    @After
    fun tearDown() = TimeZone.setDefault(zone)

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            SignerTheme(darkTheme = look.dark, applySystemBarAppearance = false) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, look.fontScale),
                    LocalLayoutDirection provides if (look.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Box(Modifier.fillMaxSize().background(SignerTheme.colors.page)) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/activity/$name-${look.name.lowercase()}.png")
    }

    private val days = listOf(
        ActivityDay(
            LocalDate.of(2026, 10, 20),
            listOf(
                ActivityEntry("a", "Approve token spending", "Unlimited USDT to Permit2", WalletLabel.Named("Public fixture 1"), utc(20, 17, 42), Chain.Evm, "1"),
                ActivityEntry("b", "Send bitcoin", "0.015 BTC to bc1qar0s…5mdq", WalletLabel.Named("Travel Signer"), utc(20, 9, 5), Chain.Bitcoin, null),
            ),
        ),
        ActivityDay(
            LocalDate.of(2026, 10, 18),
            listOf(
                ActivityEntry("c", "Transfer TRX", "120 TRX", WalletLabel.Deleted, utc(18, 21, 30), Chain.Tron, null),
                ActivityEntry("d", "Sign message", "", WalletLabel.Named("Public fixture 1"), utc(18, 11, 0), Chain.Evm, "137"),
                ActivityEntry("e", "Transfer SOL", "1.5 SOL", WalletLabel.Named("Public fixture 1"), utc(18, 8, 12), Chain.Solana, null),
            ),
        ),
    )

    @Test
    fun list() = capture("01-activity-list") {
        ActivityContent(ActivityUiState(loading = false, days = days), onOpenReceipt = {}, onClear = {}, onConfirmClear = {}, onDismissClear = {})
    }

    @Test
    fun empty() = capture("02-activity-empty") {
        ActivityContent(ActivityUiState(loading = false), onOpenReceipt = {}, onClear = {}, onConfirmClear = {}, onDismissClear = {})
    }

    @Test
    fun unreadable() = capture("03-activity-unreadable") {
        ActivityContent(ActivityUiState(loading = false, unreadable = true), onOpenReceipt = {}, onClear = {}, onConfirmClear = {}, onDismissClear = {})
    }

    @Test
    fun detailDecoded() = capture("04-activity-detail") {
        ActivityDetailContent(
            state = ActivityDetailUiState(
                receipt = receipt("a", riskLevel = "warning"),
                wallet = WalletLabel.Named("Public fixture 1"),
                fingerprint = "73C5DA0A",
                risk = com.bittool.signer.feature.activity.RiskRow.WarningsShown,
                decode = DecodeState.Decoded(ActivityTestData.approvalReview),
            ),
            onBack = {},
            onOpenDecodedFields = {},
            onShowQr = {},
            onDismissQr = {},
            onExport = {},
        )
    }

    @Test
    fun detailDecoding() = capture("05-activity-detail-decoding") {
        ActivityDetailContent(
            state = ActivityDetailUiState(receipt = receipt("a"), wallet = WalletLabel.Deleted, fingerprint = "73C5DA0A", decode = DecodeState.Decoding),
            onBack = {},
            onOpenDecodedFields = {},
            onShowQr = {},
            onDismissQr = {},
            onExport = {},
        )
    }

    @Test
    fun signedRequest() = capture("06-signed-request") {
        SignedRequestContent(decode = DecodeState.Decoded(ActivityTestData.approvalReview), onBack = {})
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = Look.entries.map { arrayOf<Any>(it) }
    }
}
