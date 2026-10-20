package com.bittool.signer.core.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.catalog.CatalogSection
import com.bittool.signer.core.designsystem.catalog.CatalogSectionHeader
import com.bittool.signer.core.designsystem.catalog.DesignCatalogSections
import com.bittool.signer.core.designsystem.component.NavIcon
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AccountProfiles
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.CompatibleWallets
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.NetworkBrand
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.registry.ContractCategory
import com.bittool.signer.core.model.review.ReviewAssetKind
import com.bittool.signer.core.model.review.ReviewContract
import com.bittool.signer.core.model.review.ReviewField
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.ui.address.AddressText
import com.bittool.signer.core.ui.address.CopyableAddress
import com.bittool.signer.core.ui.address.CopyableText
import com.bittool.signer.core.ui.address.HashText
import com.bittool.signer.core.ui.legal.LegalDocumentView
import com.bittool.signer.core.ui.logo.ChainGlyph
import com.bittool.signer.core.ui.logo.GenericNetworkLogo
import com.bittool.signer.core.ui.logo.NetworkLogo
import com.bittool.signer.core.ui.logo.NetworkLogoStack
import com.bittool.signer.core.ui.logo.TokenIcon
import com.bittool.signer.core.ui.pairing.AddressIndexControl
import com.bittool.signer.core.ui.pairing.ChainPicker
import com.bittool.signer.core.ui.pairing.IntendedWallets
import com.bittool.signer.core.ui.pairing.ProfileMenu
import com.bittool.signer.core.ui.password.PasswordField
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.core.ui.password.PasswordRequirements
import com.bittool.signer.core.ui.payload.PayloadDisclosure
import com.bittool.signer.core.ui.review.ContractCategoryGlyph
import com.bittool.signer.core.ui.review.ContractNotice
import com.bittool.signer.core.ui.review.NftGlyph
import com.bittool.signer.core.ui.review.ReviewDetailsCard
import com.bittool.signer.core.ui.review.ReviewSummaryCard
import com.bittool.signer.core.ui.review.ReviewWarnings
import com.bittool.signer.core.ui.text.localizedTitle
import com.bittool.signer.core.ui.text.rememberReviewText
import com.bittool.signer.core.ui.wallet.WalletAppIcon
import com.bittool.signer.core.ui.wallet.WalletAvatar
import com.bittool.signer.core.ui.wallet.WalletIdentityHeader

/**
 * Public sample data of the catalog and the screenshot tests: published test vectors and the engine display fixtures
 * of the iOS NFTReviewDisplayTests. Nothing secret.
 */
object CatalogSamples {
    /** BIP44 account 0 address of the published "abandon … about" vector. */
    const val EVM_ADDRESS = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
    const val BITCOIN_ADDRESS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    const val TRON_ADDRESS = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"
    const val REQUEST_HASH = "3f2a91c40b77d2e85a10c3f98e4401ab7c55d0e2919f6b3a04ce7d81f2a9b360"
    const val SPENDER = "0xBAbAbabAbabaBABaBAbABabaBAbAbaBaBAbABaBa"

    /** A review around an engine `display` object, as NFTReviewDisplayTests builds it. */
    fun review(display: String, chain: String = "evm", extra: String = ""): TransactionReview {
        val json = """
            {"chain": "$chain", "title": "Contract call", "summary": "", "requestHash": "$REQUEST_HASH",
             "fields": [{"label": "Network", "value": "Ethereum"}], "warnings": [], "decodeStatus": "fullyDecoded",
             "riskLevel": "normal", "network": {"chainId": "1", "name": "Ethereum", "symbol": "ETH"},
             "display": $display $extra}
        """.trimIndent()
        return TransactionReview.parse(requireNotNull(parseJsonObject(json)) { "invalid sample" })
    }

    /** The engine display fixtures of NFTReviewDisplayTests (`BitToolSignerTests/NFTReviewDisplayTests.swift:21-31`). */
    val nftDisplays: Map<String, String> = mapOf(
        "ensTransfer" to """{"action": "Send NFT", "headline": "NFT #79233663…80642508 (77 digits)", "counterpartyLabel": "To", "counterparty": "$SPENDER", "assetKind": "nft", "assetContract": "0x57f1887a8BF19b14fC0dF6Fd9B2acc9Af147eA85", "assetContractLabel": "NFT contract (not verified)", "tokenId": "79233663829379634837589865448569342784712482819484549289560981379859480642508", "fee": "0.0002 ETH"}""",
        "ambiguousApproveHuge" to """{"action": "Approve (NFT or token)", "headline": "NFT #57896044…64819968 (77 digits) — or 57896044…64819968 (77 digits) raw token units if this is an ERC-20", "counterpartyLabel": "Spender", "counterparty": "$SPENDER", "assetKind": "nftOrToken", "assetContract": "0x1111111111111111111111111111111111111111", "assetContractLabel": "Contract (NFT or token)", "tokenId": "57896044618658097711785492504343953926634992332820282019728792003956564819968", "fee": "0.0002 ETH"}""",
        "ambiguousApproveUnlimited" to """{"action": "Approve (NFT or token)", "amount": "Unlimited", "symbol": "tokens", "headline": "NFT #11579208…29639935 (78 digits) — or unlimited raw token units if this is an ERC-20", "counterpartyLabel": "Spender", "counterparty": "$SPENDER", "assetKind": "nftOrToken", "assetContract": "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D", "assetContractLabel": "Contract (NFT or token)", "tokenId": "115792089237316195423570985008687907853269984665640564039457584007913129639935", "fee": "0.0002 ETH"}""",
        "ambiguousTransferShort" to """{"action": "Transfer (NFT or token)", "headline": "NFT #1234 — or 1234 raw units if ERC-20", "counterpartyLabel": "To", "counterparty": "$SPENDER", "assetKind": "nftOrToken", "assetContract": "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D", "assetContractLabel": "Contract (NFT or token)", "tokenId": "1234", "fee": "0.0002 ETH"}""",
        "erc1155Single" to """{"action": "Send ERC-1155 Token", "headline": "3 × #7", "counterpartyLabel": "To", "counterparty": "$SPENDER", "assetKind": "multiToken", "assetContract": "0x1111111111111111111111111111111111111111", "assetContractLabel": "ERC-1155 contract (not verified)", "tokenId": "7", "fee": "0.0002 ETH"}""",
        "erc1155Batch" to """{"action": "Send ERC-1155 Batch", "headline": "3 items · IDs #7, #8 (+1 more)", "counterpartyLabel": "To", "counterparty": "$SPENDER", "assetKind": "multiToken", "assetContract": "0x1111111111111111111111111111111111111111", "assetContractLabel": "ERC-1155 contract (not verified)", "fee": "0.0002 ETH"}""",
        "collectionApproval" to """{"action": "Approve All NFTs", "headline": "All your NFTs in 0xBC4C…f13D", "counterpartyLabel": "Operator", "counterparty": "$SPENDER", "assetKind": "nft", "assetContract": "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D", "assetContractLabel": "NFT contract (not verified)", "fee": "0.0002 ETH"}""",
        "seaportListing" to """{"action": "List NFT for Sale (Seaport 1.6)", "amount": "0.95", "symbol": "ETH", "headline": "NFT #1234 · 0xBC4C…f13D", "counterpartyLabel": "Marketplace", "counterparty": "0x0000000000000068F116a894984e2DB1123eB395", "assetKind": "nftOrder", "assetContract": "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D", "assetContractLabel": "NFT contract (not verified)", "tokenId": "1234", "give": ["NFT #1234 · 0xBC4C…f13D"], "receive": ["0.95 ETH"], "others": ["0.025 ETH → 0x0000…a719 (OpenSea fee recipient)", "0.025 ETH → 0x1234…7890 (unrecognised recipient)"]}""",
        "seaportRawPrice" to """{"action": "List NFT for Sale (Seaport 1.6)", "amount": "100000000000000000000000000000000000000000000000000000000000000000000000000000", "symbol": "raw units", "headline": "NFT #1234 · 0xBC4C…f13D", "counterpartyLabel": "Marketplace", "counterparty": "0x0000000000000068F116a894984e2DB1123eB395", "assetKind": "nftOrder", "assetContract": "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D", "assetContractLabel": "NFT contract (not verified)", "tokenId": "1234", "give": ["NFT #1234 · 0xBC4C…f13D"], "receive": ["100000000000000000000000000000000000000000000000000000000000000000000000000000 raw units of 0x7777…7777"]}""",
        "seaportMixed" to """{"action": "Sign Seaport Order (Seaport 1.6)", "headline": "Give 2 items · receive 1 item", "counterpartyLabel": "Marketplace", "counterparty": "0x0000000000000068F116a894984e2DB1123eB395", "assetKind": "nftOrder", "assetContract": "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D", "assetContractLabel": "NFT contract (not verified)", "tokenId": "2", "give": ["NFT #2 · 0xBC4C…f13D", "0.1 ETH"], "receive": ["1 ETH"]}""",
    )

    /** A token transfer whose spender is a known public contract (the notice moves into the counterparty box). */
    val approvalReview: TransactionReview by lazy {
        review(
            display = """{"action": "Approve", "amount": "Unlimited", "symbol": "USDT", "counterpartyLabel": "Spender", "counterparty": "0x000000000022D473030F116dDEE9F6B43aC78BA3", "fee": "0.000214901273410082 ETH"}""",
            extra = """, "riskLevel": "high", "decodeStatus": "partiallyDecoded",
                "warnings": ["UNLIMITED approval: the spender can move every USDT you hold.", "The fee is unusually high for this network."],
                "contract": {"address": "0x000000000022D473030F116dDEE9F6B43aC78BA3", "role": "spender", "known": true, "name": "Permit2", "protocol": "Uniswap", "category": "infra", "everyChain": true}""",
        )
    }

    /** Details rows of NFTReviewDetailsCard (`detailsCardRendersTokenIDsAsPlainText`). */
    val detailFields: List<ReviewField> = listOf(
        ReviewField(0, "Token ID", "106686051536470969934437590839493823286117473395005282216036227418063047698617"),
        ReviewField(1, "Items", (1..8).joinToString("\n") { "#${it}000000000000000000000000000 × $it" }),
        ReviewField(2, "Instruction 1 · Program", "1".repeat(32)),
        ReviewField(3, "Derivation path", "m/44'/60'/0'/0/0"),
        ReviewField(4, "Method", "safeTransferFrom"),
    )

    val unknownSpender = ReviewContract(address = SPENDER, role = ReviewContract.Role.Spender, known = false)
    val tokenContract = ReviewContract(address = "0xdAC17F958D2ee523a2206206994597C13D831ec7", role = ReviewContract.Role.Contract, known = false, token = "Tether USD (USDT)")

    val legalBlocks: List<LegalBlock> = listOf(
        LegalBlock(0, LegalBlock.Kind.Version, "Version 1.0 · 1 October 2026"),
        LegalBlock(1, LegalBlock.Kind.Heading, "1. What the app does"),
        LegalBlock(2, LegalBlock.Kind.Paragraph, "BitTool Signer keeps keys **offline** and signs requests it can show you in full."),
        LegalBlock(3, LegalBlock.Kind.Bullet, "It never connects to the internet."),
        LegalBlock(4, LegalBlock.Kind.Bullet, "It never broadcasts transactions."),
    )
}

/** The `:core:ui` part of the design catalog (domain components with public sample data). */
val UiCatalogSections: List<CatalogSection> = listOf(
    CatalogSection("Addresses") { AddressSection() },
    CatalogSection("Network and token logos") { LogoSection() },
    CatalogSection("Wallet identity") { WalletSection() },
    CatalogSection("Passwords") { PasswordSection() },
    CatalogSection("Pairing controls") { PairingSection() },
    CatalogSection("Payload disclosure") { PayloadSection() },
    CatalogSection("Review summary") { ReviewSection() },
    CatalogSection("NFT review") { NftReviewSection() },
    CatalogSection("Review details") { DetailsSection() },
    CatalogSection("Legal document") { LegalSection() },
)

/** Every catalog section, design system first. */
val AllCatalogSections: List<CatalogSection> get() = DesignCatalogSections + UiCatalogSections

@Composable
private fun Padded(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = SignerMetrics.pagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
private fun AddressSection() = Padded {
    val t = SignerTheme.typography
    AddressText(CatalogSamples.EVM_ADDRESS, style = t.callout)
    AddressText(CatalogSamples.BITCOIN_ADDRESS, style = t.footnote)
    CopyableAddress(CatalogSamples.TRON_ADDRESS, style = t.body)
    HashText(CatalogSamples.REQUEST_HASH)
    CopyableText("support@bittool.com", copyLabel = stringResource(R.string.copy_address_2))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogoSection() = Padded {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NetworkBrand.entries.forEach { NetworkLogo(it, size = 28.dp) }
        GenericNetworkLogo(size = 28.dp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Chain.entries.forEach { ChainGlyph(it, size = 32.dp) } }
    NetworkLogoStack(size = 28.dp, ring = SignerTheme.colors.page)
    NetworkLogoStack(size = 16.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TokenIcon(iconId = null, symbol = "USDT", size = 40.dp)
        TokenIcon(iconId = "0000000000000000", symbol = "wbtc", size = 40.dp)
        ReviewAssetKind.entries.forEach { NftGlyph(it, size = 44.dp) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ContractCategory.entries.forEach { ContractCategoryGlyph(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WalletSection() = Padded {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WalletAvatar("a3f1c2", "Main", size = 50.dp)
        WalletAvatar("9c0e77", "savings", size = 50.dp)
        WalletAvatar("1d2b3c", "冷钱包", size = 50.dp)
        WalletAvatar("ff00aa", "", size = 50.dp)
    }
    WalletIdentityHeader(name = "Main", fingerprint = "73c5da0a", avatarIdentity = "a3f1c2")
    WalletIdentityHeader(name = "", fingerprint = null, avatarIdentity = "ff00aa", size = 48.dp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (CompatibleWallets.allAssets.sorted() + "wallet_missing").forEach { WalletAppIcon(it, size = 30.dp) }
    }
}

@Composable
private fun PasswordSection() = Padded {
    val empty = remember { TextFieldState() }
    val passphrase = remember { TextFieldState("correct horse") }
    val weak = remember { TextFieldState("Abc12345") }
    PasswordField(PasswordKind.WalletPassword, empty)
    PasswordField(PasswordKind.Passphrase, passphrase, isNew = true, revealable = true)
    PasswordRequirements(password = weak.text, confirmation = "")
    PasswordRequirements(password = "Tr0ub4dor&3-horse", confirmation = "Tr0ub4dor&3-horse")
}

@Composable
private fun PairingSection() = Padded {
    var chain by remember { mutableStateOf(Chain.Bitcoin) }
    ChainPicker(chain, onSelect = { chain = it })
    var profile by remember { mutableStateOf(AccountProfiles.bitcoinSingle.first()) }
    // Labels are resolved in composition; ProfileMenu takes plain lookups.
    val titles = AccountProfiles.bitcoinSingle.associateWith { it.localizedTitle() }
    ProfileMenu(
        title = stringResource(R.string.address_type),
        options = AccountProfiles.bitcoinSingle,
        selection = profile,
        label = { titles.getValue(it) },
        detail = { it.detail },
        onSelect = { profile = it },
    )
    var index by remember { mutableLongStateOf(0L) }
    AddressIndexControl(index = index, onIndexChange = { index = it }, pathPrefix = AccountProfiles.TRON_PATH_PREFIX)
    IntendedWallets(wallets = CompatibleWallets.wallets(Chain.Bitcoin, "native", "accountXpub"), onSubmitWallet = {}, chain = Chain.Bitcoin)
}

@Composable
private fun PayloadSection() = Padded {
    PayloadDisclosure(
        title = stringResource(R.string.payload_json),
        text = "{\n  \"type\" : \"crypto-account\",\n  \"masterFingerprint\" : 1942346250\n}",
        notes = listOf("Fingerprints are stored as 32-bit numbers: 1942346250 is fingerprint 73C5DA0A."),
    )
}

@Composable
private fun ReviewSection() = Padded {
    val text = rememberReviewText()
    val review = CatalogSamples.approvalReview
    ReviewSummaryCard(review, text)
    ReviewWarnings(review.warnings)
    ContractNotice(CatalogSamples.unknownSpender)
    ContractNotice(CatalogSamples.tokenContract)
}

@Composable
private fun NftReviewSection() = Padded {
    val text = rememberReviewText()
    listOf("seaportListing", "ambiguousTransferShort").forEach { name ->
        ReviewSummaryCard(CatalogSamples.review(CatalogSamples.nftDisplays.getValue(name)), text)
    }
}

@Composable
private fun DetailsSection() = Padded {
    ReviewDetailsCard(CatalogSamples.detailFields, rememberReviewText())
}

@Composable
private fun LegalSection() {
    Box(Modifier.fillMaxWidth().height(260.dp)) { LegalDocumentView(CatalogSamples.legalBlocks) }
}

/** Developer labels of the QA catalog (debug builds only, never part of the shipped interface, so not localised). */
private object CatalogLabels {
    const val TITLE = "Design catalog"
    const val DARK = "Toggle dark appearance"
    const val RTL = "Toggle right-to-left layout"
}

/**
 * The design catalog as a screen for later QA (debug builds host it): every section of [AllCatalogSections] in one
 * scrolling list, with top-bar toggles for dark mode and right-to-left layout. Not part of the shipped navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignCatalogScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val systemDark = SignerTheme.isDark
    var dark by rememberSaveable { mutableStateOf(systemDark) }
    var rtl by rememberSaveable { mutableStateOf(false) }
    SignerTheme(darkTheme = dark, applySystemBarAppearance = false) {
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Scaffold(
                modifier = modifier,
                containerColor = SignerTheme.colors.page,
                topBar = {
                    SignerTopAppBar(
                        title = CatalogLabels.TITLE,
                        navigation = NavIcon.Back,
                        onNavigate = onBack,
                        actions = {
                            IconButton(onClick = { dark = !dark }) { Icon(SignerIcons.Contrast, contentDescription = CatalogLabels.DARK) }
                            IconButton(onClick = { rtl = !rtl }) { Icon(SignerIcons.SwapHoriz, contentDescription = CatalogLabels.RTL) }
                        },
                    )
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().background(SignerTheme.colors.page),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + SignerMetrics.listBottomInset),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(AllCatalogSections, key = { it.title }) { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CatalogSectionHeader(section.title)
                            section.content()
                        }
                    }
                }
            }
        }
    }
}
