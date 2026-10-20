package com.bittool.signer.feature.settings.screenshot

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
import androidx.lifecycle.SavedStateHandle
import com.bittool.signer.core.common.Distribution
import com.bittool.signer.core.data.NoticeSection
import com.bittool.signer.core.data.testing.FakeLegalDocumentRepository
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.AppLanguage
import com.bittool.signer.core.model.Appearance
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.feature.settings.SettingsContent
import com.bittool.signer.feature.settings.SettingsTestData
import com.bittool.signer.feature.settings.SettingsUiState
import com.bittool.signer.feature.settings.about.ContactScreen
import com.bittool.signer.feature.settings.about.LegalDocumentContent
import com.bittool.signer.feature.settings.about.LegalDocumentUiState
import com.bittool.signer.feature.settings.about.Measured
import com.bittool.signer.feature.settings.about.NoticesUiState
import com.bittool.signer.feature.settings.about.OpenSourceContent
import com.bittool.signer.feature.settings.about.OpenSourceUiState
import com.bittool.signer.feature.settings.about.SubmitWalletScreen
import com.bittool.signer.feature.settings.about.ThirdPartyNoticesContent
import com.bittool.signer.feature.settings.compatibility.CompatibilityContent
import com.bittool.signer.feature.settings.compatibility.CompatibilityTab
import com.bittool.signer.feature.settings.compatibility.CompatibilityViewModel
import com.bittool.signer.feature.settings.language.LanguageContent
import com.bittool.signer.feature.settings.language.LanguageUiState
import com.bittool.signer.feature.settings.reference.NetworkReferenceContent
import com.bittool.signer.feature.settings.reference.NetworkReferenceUiState
import com.bittool.signer.feature.settings.reference.NetworkRowModel
import com.bittool.signer.feature.settings.reference.NetworksContent
import com.bittool.signer.feature.settings.reference.NetworksUiState
import com.bittool.signer.feature.settings.reference.ReferenceTitle
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.util.Locale
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

/** The Settings screens recorded for visual QA into `feature/settings/build/outputs/roborazzi/` (record only). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenshotTest(private val look: Look) {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        if (look.rtl) RuntimeEnvironment.setQualifiers("+ar")
    }

    private fun capture(name: String, wholeScreen: Boolean = false, content: @Composable () -> Unit) {
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
        val path = "build/outputs/roborazzi/settings/$name-${look.name.lowercase()}.png"
        // A dialog is a window of its own: capture every window then.
        if (wholeScreen) captureScreenRoboImage(path) else compose.onRoot().captureRoboImage(path)
    }

    private val compatibility = CompatibilityViewModel(SavedStateHandle())

    @Test
    fun settings() = capture("01-settings") {
        SettingsContent(
            state = SettingsUiState(
                appLockEnabled = true,
                appLockToggleEnabled = true,
                biometricsAvailable = true,
                appearance = Appearance.System,
                language = AppLanguage.System,
                version = "1.0.0 (1)",
            ),
            onAppLockChange = {},
            onThemeClick = {},
            onThemeSelected = {},
            onThemeDismissed = {},
            onNavigate = {},
        )
    }

    @Test
    fun settingsAppLockError() = capture("02-settings-applock-error", wholeScreen = true) {
        SettingsContent(
            state = SettingsUiState(
                appLockToggleEnabled = true,
                biometricsAvailable = false,
                appLockError = com.bittool.signer.core.model.UiText.Ios("Authentication failed. Try again or use a wallet password."),
                appearance = Appearance.Dark,
                language = AppLanguage.German,
                version = "1.0.0 (1)",
                showsThemeDialog = true,
            ),
            onAppLockChange = {},
            onThemeClick = {},
            onThemeSelected = {},
            onThemeDismissed = {},
            onNavigate = {},
        )
    }

    @Test
    fun language() = capture("03-language") {
        LanguageContent(LanguageUiState(AppLanguage.German, Locale.forLanguageTag("en-GB")), onSelect = {}, onBack = {})
    }

    @Test
    fun networks() = capture("04-networks") {
        val reference = SettingsTestData.reference
        NetworksContent(
            state = NetworksUiState(
                loading = false,
                registryVersion = "2026-09-22.1",
                networks = listOf(
                    NetworkRowModel("ethereum", "evm:1", "Ethereum", "ETH", com.bittool.signer.core.model.NetworkBrand.Ethereum, 1008, 329),
                    NetworkRowModel("smartchain", "evm:56", "BNB Smart Chain", "BNB", com.bittool.signer.core.model.NetworkBrand.Bnb, 876, 194),
                    NetworkRowModel("tron", "tron:", "TRON", "TRX", com.bittool.signer.core.model.NetworkBrand.Tron, 26, 139),
                    NetworkRowModel("solana", "solana:", "Solana", "SOL", com.bittool.signer.core.model.NetworkBrand.Solana, 585, 262),
                    NetworkRowModel("gnosis", "evm:100", "Gnosis", "xDAI", null, 32, 0),
                ),
                everyChainContracts = 27,
                contractCount = 1720,
                contractsUpdated = reference.contractsUpdated,
            ),
            onBack = {},
            onOpenNetwork = {},
        )
    }

    @Test
    fun networkTokens() = capture("05-network-tokens") {
        NetworkReferenceContent(
            state = NetworkReferenceUiState(
                loading = false,
                title = ReferenceTitle.Network("Ethereum"),
                tokenCount = 3,
                contractCount = 2,
                tokens = SettingsTestData.reference.tokensByChain.getValue("evm:1"),
            ),
            onBack = {},
            onQueryChange = {},
            onSegmentSelected = {},
        )
    }

    @Test
    fun networkContracts() = capture("06-network-contracts") {
        NetworkReferenceContent(
            state = NetworkReferenceUiState(
                loading = false,
                title = ReferenceTitle.EveryEvmNetwork,
                tokenCount = 0,
                contractCount = 2,
                showsContracts = true,
                query = "seaport",
                appliedQuery = "seaport",
                contracts = listOf(SettingsTestData.seaport),
            ),
            onBack = {},
            onQueryChange = {},
            onSegmentSelected = {},
        )
    }

    @Test
    fun compatibilityFormats() = capture("07-compatibility-formats") {
        CompatibilityContent(CompatibilityTab.Formats, compatibility.chains, compatibility.walletLevel, compatibility.directory, {}, {}, {})
    }

    @Test
    fun compatibilityWallets() = capture("08-compatibility-wallets") {
        CompatibilityContent(CompatibilityTab.Wallets, compatibility.chains, compatibility.walletLevel, compatibility.directory, {}, {}, {})
    }

    @Test
    fun openSource() = capture("09-open-source") {
        OpenSourceContent(
            state = OpenSourceUiState(
                version = "1.0.0 (1)",
                repository = SettingsTestData.buildInfo.repositoryUrl,
                distribution = Distribution.DirectApk,
                signingCertificate = SettingsTestData.buildInfo.signingCertificateSha256,
                engineDigest = Measured.Value("6933d0d8a6f60faa8222506ac0bd7eef80eb884c9f73e31e9f354b4740f21077"),
                engineBytes = Measured.Value(1_181_026),
                registryVersion = Measured.Reading,
            ),
            onBack = {},
            onOpenNotices = {},
        )
    }

    @Test
    fun contact() = capture("10-contact") { ContactScreen(onBack = {}) }

    @Test
    fun submitWallet() = capture("11-submit-wallet") { SubmitWalletScreen(onBack = {}) }

    @Test
    fun legal() = capture("12-legal") {
        val text = "Privacy Policy\nVersion 1.1 · Effective 16 October 2026\n\n## In short\nBitTool Signer **collects nothing**. It has no " +
            "networking code.\n\n- No account\n- No analytics\n\n## 12. Contact\nBITTOOL PTE. LTD.\n\nsupport@bittool.com\n\nhttps://bittool.com/signer"
        LegalDocumentContent(LegalDocumentUiState(LegalDocument.Privacy, FakeLegalDocumentRepository.parsed(LegalDocument.Privacy, text)), onBack = {})
    }

    @Test
    fun notices() = capture("13-notices") {
        ThirdPartyNoticesContent(
            NoticesUiState(
                listOf(
                    NoticeSection(
                        "BitTool Signer JavaScript engine — third-party notices",
                        listOf("@noble/curves 1.9.7\nMIT License\nCopyright (c) 2022 Paul Miller (https://paulmillr.com)", "=".repeat(60)),
                    ),
                    NoticeSection("BitTool Signer for Android — third-party notices", listOf("AndroidX\nApache License 2.0")),
                ),
            ),
            onBack = {},
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = Look.entries.map { arrayOf<Any>(it) }
    }
}
