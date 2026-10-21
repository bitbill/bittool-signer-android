package com.bittool.signer.feature.wallets.screenshot

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.ConnectOptions
import com.bittool.signer.core.model.PairingLevel
import com.bittool.signer.feature.wallets.WalletFixtures
import com.bittool.signer.feature.wallets.addresses.AddressDetailSheet
import com.bittool.signer.feature.wallets.addresses.AddressesActions
import com.bittool.signer.feature.wallets.addresses.AddressesContent
import com.bittool.signer.feature.wallets.addresses.AddressesUiState
import com.bittool.signer.feature.wallets.addresses.addressTitle
import com.bittool.signer.feature.wallets.common.WalletIdentity
import com.bittool.signer.feature.wallets.connect.ConnectActions
import com.bittool.signer.feature.wallets.connect.ConnectContent
import com.bittool.signer.feature.wallets.connect.ConnectUiState
import com.bittool.signer.feature.wallets.detail.WalletDetailActions
import com.bittool.signer.feature.wallets.detail.WalletDetailContent
import com.bittool.signer.feature.wallets.detail.WalletDetailUiState
import com.bittool.signer.feature.wallets.list.WalletsActions
import com.bittool.signer.feature.wallets.list.WalletsContent
import com.bittool.signer.feature.wallets.list.WalletsUiState
import com.bittool.signer.feature.wallets.security.ChangePasswordContent
import com.bittool.signer.feature.wallets.security.DeleteWalletActions
import com.bittool.signer.feature.wallets.security.DeleteWalletContent
import com.bittool.signer.feature.wallets.security.DeleteWalletUiState
import com.bittool.signer.feature.wallets.security.PasswordFields
import com.bittool.signer.feature.wallets.security.RepairWalletContent
import com.bittool.signer.feature.wallets.security.SecurityPageUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/** The looks every screen is recorded in: light and dark (the ask), plus font scale 2.0 and Arabic RTL for QA. */
enum class Look(val dark: Boolean, val fontScale: Float = 1f, val rtl: Boolean = false) {
    Light(false),
    Dark(true),
    FontScale2(false, fontScale = 2f),
    Rtl(true, rtl = true),
}

/**
 * The recorded screens, with public fixture data only. [captureTag] records that node instead of the activity's root: a
 * sheet is a window of its own.
 */
enum class Screen(val heightDp: Int, val content: @Composable () -> Unit, val captureTag: String? = null) {
    Wallets(900, {
        val state = WalletFixtures.state(repair = true, duplicates = true)
        WalletsContent(WalletsUiState(state.wallets.map { WalletIdentity.of(state, it.id)!! }, loaded = true), WalletsActions())
    }),
    WalletDetail(1000, {
        val state = WalletFixtures.state()
        WalletDetailContent(
            uiState = WalletDetailUiState(walletId = WalletFixtures.readyId, identity = WalletIdentity.of(state, WalletFixtures.readyId)),
            actions = WalletDetailActions(),
            toast = rememberStatusToastState(),
            onCopyFingerprint = {},
            onRename = {},
        )
    }),
    WalletDetailRepair(900, {
        val state = WalletFixtures.state(repair = true)
        WalletDetailContent(
            uiState = WalletDetailUiState(walletId = WalletFixtures.repairId, identity = WalletIdentity.of(state, WalletFixtures.repairId)),
            actions = WalletDetailActions(),
            toast = rememberStatusToastState(),
            onCopyFingerprint = {},
            onRename = {},
        )
    }),
    Addresses(1100, {
        AddressesContent(
            AddressesUiState(chain = Chain.Bitcoin, accounts = WalletFixtures.accounts(Chain.Bitcoin, 0, 12), loaded = true),
            AddressesActions(),
        )
    }),
    AddressesSolana(700, {
        AddressesContent(AddressesUiState(chain = Chain.Solana, accounts = WalletFixtures.solanaAccounts, loaded = true), AddressesActions())
    }),

    /** The address sheet: its card on the sheet colour (QA wallets-02) and the brightness capsule on the plate (wallets-06). */
    AddressSheet(1000, {
        val account = WalletFixtures.accounts(Chain.Bitcoin, 0, 1).first()
        AddressDetailSheet(account = account, title = addressTitle(account, Chain.Bitcoin), onDismiss = {})
    }, captureTag = "address.sheet"),
    PairWalletLevel(2400, {
        ConnectContent(
            uiState = ConnectUiState(level = PairingLevel.Wallet, wallet = WalletFixtures.walletPayload(), masterFingerprint = "73C5DA0A"),
            toast = rememberStatusToastState(),
            actions = ConnectActions(),
        )
    }),
    PairBitcoin(1400, {
        ConnectContent(
            uiState = ConnectUiState(level = PairingLevel.Coin, coin = WalletFixtures.coinPayload(), masterFingerprint = "73C5DA0A"),
            toast = rememberStatusToastState(),
            actions = ConnectActions(),
        )
    }),
    PairTron(1300, {
        ConnectContent(
            uiState = ConnectUiState(
                level = PairingLevel.Coin,
                options = ConnectOptions(chain = Chain.Tron, tronIndex = 12),
                coin = WalletFixtures.coinPayload(path = "m/44'/195'/0'/0/12", address = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH", mode = "address"),
                masterFingerprint = "73C5DA0A",
            ),
            toast = rememberStatusToastState(),
            actions = ConnectActions(),
        )
    }),
    ChangePassword(900, {
        val fields = remember { PasswordFields(3) }
        ChangePasswordContent(
            uiState = SecurityPageUiState(identity = WalletIdentity.of(WalletFixtures.state(), WalletFixtures.readyId)),
            fields = fields,
            toast = rememberStatusToastState(),
            onSubmit = {},
            onCancel = {},
        )
    }),
    DeleteWallet(800, {
        DeleteWalletContent(
            uiState = DeleteWalletUiState(identity = WalletIdentity.of(WalletFixtures.state(), WalletFixtures.readyId), confirmed = true),
            toast = rememberStatusToastState(),
            actions = DeleteWalletActions(),
        )
    }),
    DeleteWalletChecking(500, {
        DeleteWalletContent(
            uiState = DeleteWalletUiState(identity = WalletIdentity.of(WalletFixtures.state(), WalletFixtures.readyId), checkingDeviceKey = true),
            toast = rememberStatusToastState(),
            actions = DeleteWalletActions(),
        )
    }),
    RemoveLostKeyWallet(900, {
        DeleteWalletContent(
            uiState = DeleteWalletUiState(
                identity = WalletIdentity.of(WalletFixtures.state(), WalletFixtures.readyId),
                confirmed = true,
                deviceKeyLost = true,
            ),
            toast = rememberStatusToastState(),
            actions = DeleteWalletActions(),
        )
    }),
    RepairWallet(700, {
        val state = WalletFixtures.state(repair = true)
        RepairWalletContent(
            uiState = SecurityPageUiState(identity = WalletIdentity.of(state, WalletFixtures.repairId)),
            fields = remember { PasswordFields(1) },
            toast = rememberStatusToastState(),
            onSubmit = {},
            onCancel = {},
        )
    }),
}

/**
 * Records every main Wallets screen to `feature/wallets/build/outputs/roborazzi/wallets/` for visual QA (record only, no
 * goldens; `-Psigner.screenshots=false` skips it). QR codes are encoded off the main thread, so each capture waits
 * until every plate on screen is ready.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletsScreenshotTest(private val screen: Screen, private val look: Look) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun record() {
        // Tall enough for the whole page (doubled at font scale 2.0), so every capture shows the complete screen.
        val height = if (look.fontScale > 1f) screen.heightDp * 2 else screen.heightDp
        RuntimeEnvironment.setQualifiers("w400dp-h${height}dp-xhdpi")
        if (look.rtl) {
            RuntimeEnvironment.setQualifiers("+ar")
            // The app declares android:supportsRtl, this library's test manifest does not: without the flag a sheet's
            // own window, whose views resolve their layout direction themselves, would stay left to right.
            RuntimeEnvironment.getApplication().applicationInfo.flags =
                RuntimeEnvironment.getApplication().applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            SignerTheme(darkTheme = look.dark, applySystemBarAppearance = false) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, look.fontScale),
                    LocalLayoutDirection provides if (look.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) { screen.content() }
            }
        }
        awaitQrCodes()
        compose.mainClock.advanceTimeBy(1_000)
        val node = screen.captureTag?.let { compose.onNodeWithTag(it) } ?: compose.onRoot()
        node.captureRoboImage("build/outputs/roborazzi/wallets/${screen.name}-${look.name.lowercase()}.png")
    }

    /** Lets the off-main QR encoding finish: every QR plate gets its content description once its image is ready. */
    private fun awaitQrCodes() {
        val plates = SemanticsMatcher("qr plate") { it.config.getOrNull(SemanticsProperties.TestTag) == "qr.plate" }
        repeat(200) {
            compose.mainClock.advanceTimeBy(50)
            val nodes = compose.onAllNodes(plates, useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes.all { it.config.contains(SemanticsProperties.ContentDescription) }) return
            Thread.sleep(10)
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> = Screen.entries.flatMap { screen ->
            // Light and dark for every screen; the large-font and RTL looks for the densest ones, the Solana label column
            // (QA wallets-13) and the sheet's mirrored brightness capsule (wallets-06).
            val everyLook = setOf(Screen.Wallets, Screen.WalletDetail, Screen.PairBitcoin, Screen.AddressesSolana, Screen.AddressSheet)
            val looks = if (screen in everyLook) Look.entries else listOf(Look.Light, Look.Dark)
            looks.map { arrayOf<Any>(screen, it) }
        }
    }
}
