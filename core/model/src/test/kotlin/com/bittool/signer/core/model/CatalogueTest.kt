package com.bittool.signer.core.model

import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Ports of `CompatibleWalletsTests.swift` (catalogue rules; icon presence is a `:core:ui` test), the password policy
 * vectors of `SecurityTests.swift`, `AppNameTests`' direction checks, `LegalDocumentTests.aBlankLineStartsANewBlock` and
 * the [UiText] fallback formatter.
 */
class CatalogueTest {
    private val every: List<CompatibleWallet> get() = Chain.entries.flatMap(CompatibleWallets::all) + CompatibleWallets.walletLevel

    @Test
    fun everyListedWalletHasAnAssetAndNoRemovedBrandRemains() {
        for (wallet in every) {
            assertTrue(wallet.asset.startsWith("wallet_"), wallet.name)
            assertTrue(wallet.platforms.isNotEmpty(), wallet.name)
            assertTrue(wallet.site.isNotEmpty(), wallet.name)
        }
        val names = every.map { it.name }.toSet()
        assertFalse("Ownbit" in names)
        assertFalse("Safe" in names)
        assertEquals(listOf("BitTool Wallet"), CompatibleWallets.walletLevel.map { it.name })
        assertEquals(24, CompatibleWallets.allAssets.size)
    }

    @Test
    fun bitcoinAccountExportsListHdWalletsThatSignByQr() {
        for (profile in listOf("native", "taproot", "nested", "legacy")) {
            val wallets = CompatibleWallets.wallets(Chain.Bitcoin, profile, "accountXpub")
            assertTrue(wallets.all { it.support == CompatibleWallet.Support.Signing })
            assertTrue(listOf("Sparrow", "BlueWallet", "Nunchuk").all { n -> wallets.any { it.name == n } })
            assertEquals(profile != "legacy", wallets.any { it.name == "ZEUS" })
        }
        assertTrue(CompatibleWallets.wallets(Chain.Bitcoin, "multisig-native", "accountXpub").all { it.support == CompatibleWallet.Support.Signing })
        assertTrue(CompatibleWallets.wallets(Chain.Bitcoin, "native", "address").all { it.support == CompatibleWallet.Support.WatchOnly })
    }

    @Test
    fun addressExportsAndTronAreWatchOnly() {
        for (chain in listOf(Chain.Evm, Chain.Solana)) {
            assertTrue(CompatibleWallets.wallets(chain, "standard", "address").all { it.support == CompatibleWallet.Support.WatchOnly })
            assertTrue(CompatibleWallets.wallets(chain, "standard", "wallet").all { it.support == CompatibleWallet.Support.Signing })
        }
        val tron = CompatibleWallets.wallets(Chain.Tron, "standard", "address")
        assertTrue(tron.isNotEmpty() && tron.all { it.support == CompatibleWallet.Support.WatchOnly })
        assertTrue(tron.any { it.name == "TokenPocket" })
        assertTrue(CompatibleWallets.wallets(Chain.Solana, "standard", "address").any { it.name == "TokenPocket" })
        assertTrue(CompatibleWallets.wallets(Chain.Evm, "standard", "wallet").any { it.name == "TokenPocket" && it.support == CompatibleWallet.Support.Signing })
    }

    @Test
    fun settingsListsEachWalletOncePerChainWithSigningFirst() {
        for (chain in Chain.entries) {
            val all = CompatibleWallets.all(chain)
            assertEquals(all.size, all.map { it.name }.toSet().size)
            val firstWatch = all.indexOfFirst { it.support == CompatibleWallet.Support.WatchOnly }.let { if (it < 0) all.size else it }
            assertTrue(all.take(firstWatch).all { it.support == CompatibleWallet.Support.Signing })
        }
    }

    @Test
    fun theDirectoryListsTwentyFourWalletsAlphabetically() {
        val directory = CompatibleWallets.directory
        assertEquals(
            listOf(
                "Ambire", "Backpack", "Bitcoin Keeper", "Bitcoin Safe", "BitTool Wallet", "BlueWallet", "BULL Bitcoin", "Electrum",
                "Helium Wallet", "imToken", "Jupiter", "MetaMask", "Nunchuk", "OKX Wallet", "OneKey", "Phantom", "Rabby", "Solflare",
                "Sparrow", "Specter", "TokenPocket", "TronLink", "Trust Wallet", "ZEUS",
            ),
            directory.map { it.name },
        )
        assertEquals("iOS · Android", directory.first { it.name == "OKX Wallet" }.platforms, "the last watch-only record wins")
        assertEquals(CompatibleWallet.Support.Signing, directory.first { it.name == "TokenPocket" }.support)
        assertEquals("iOS · Android", directory.first { it.name == "TokenPocket" }.platforms)
    }

    @Test
    fun passwordRulesAndBoundaries() {
        assertTrue(PasswordPolicy.evaluate("Abcdefg1").isValid)
        assertTrue(PasswordPolicy.evaluate("abcdef1!").isValid)
        assertFalse(PasswordPolicy.evaluate("Abcde1!").isValid)
        assertFalse(PasswordPolicy.evaluate("alllowercase").isValid)
        assertFalse(PasswordPolicy.evaluate("ABCDEFG1").isValid)
        assertFalse(PasswordPolicy.evaluate("Abcdefg\n1").isValid)
        assertTrue(PasswordPolicy.evaluate("Aa1" + "x".repeat(61)).isValid)
        assertFalse(PasswordPolicy.evaluate("Aa1" + "x".repeat(62)).isValid)
        assertTrue(PasswordPolicy.evaluate("Spaces are Allowed1!").isValid)
        // Swift parity details (vault-crypto.md §4).
        assertTrue(PasswordPolicy.evaluate("Abc👍🏽defg").let { it.hasValidLength && it.categories == 3 })
        assertFalse(PasswordPolicy.evaluate("Abcdefg1👩‍👩‍👧").isValid, "ZWJ (Cf) is a control character")
        assertEquals(2, PasswordPolicy.evaluate("密码密码密码1!").categories)
        assertEquals(UiText.Ios("Use 8–64 characters."), PasswordPolicy.evaluate("Ab1!").message)
        assertEquals(UiText.Ios("Control characters are not allowed."), PasswordPolicy.evaluate("Abcdefg­1").message)
        assertEquals(UiText.Ios("Include at least 3 of: uppercase, lowercase, numbers and symbols."), PasswordPolicy.evaluate("abcdefgh1").message)
        assertEquals(UiText.Ios("Password meets the requirements."), PasswordPolicy.evaluate("Abcdefg1").message)
        // 34 grapheme clusters but over 1,024 UTF-8 bytes (combining marks): the byte cap applies too.
        assertFalse(PasswordPolicy.evaluate("Aa1!" + ("e" + "\u0301".repeat(20)).repeat(30)).hasValidLength)
    }

    @Test
    fun strengthLevels() {
        assertEquals(0, PasswordPolicy.strength(""))
        assertEquals(1, PasswordPolicy.strength("abc"))
        assertEquals(2, PasswordPolicy.strength("Abcdefg1"))
        assertEquals(3, PasswordPolicy.strength("Abcdefghijk1"))
        assertEquals(3, PasswordPolicy.strength("Abcdefghijklmno1"))
        assertEquals(4, PasswordPolicy.strength("Abcdefghijklmn1!"))
        assertTrue(PasswordPolicy.matches("a", "a"))
        assertFalse(PasswordPolicy.matches("a", ""))
        assertFalse(PasswordPolicy.matches("", ""))
        assertFalse(PasswordPolicy.matches("a", "b"))
    }

    @Test
    fun languagesAreTheTwentyOneBundledOnes() {
        assertEquals(22, AppLanguage.entries.size)
        assertEquals(true, AppLanguage.Arabic.isRightToLeft)
        assertEquals(false, AppLanguage.English.isRightToLeft)
        assertNull(AppLanguage.System.isRightToLeft)
        assertEquals(AppLanguage.System, AppLanguage.fromCode("klingon"))
        assertEquals(AppLanguage.LatinAmericanSpanish, AppLanguage.fromCode("es-419"))
        assertEquals("ar-u-nu-latn", AppLanguage.Arabic.languageTag)
        assertEquals(AppLanguage.TraditionalChinese, AppLanguage.fromLocale(Locale.forLanguageTag("zh-Hant")))
        assertEquals(AppLanguage.TraditionalChinese, AppLanguage.fromLocale(Locale.forLanguageTag("zh-TW")))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.fromLocale(Locale.forLanguageTag("zh-CN")))
        assertEquals(AppLanguage.LatinAmericanSpanish, AppLanguage.fromLocale(Locale.forLanguageTag("es-MX")))
        assertEquals(AppLanguage.Spanish, AppLanguage.fromLocale(Locale.forLanguageTag("es-ES")))
        assertEquals(AppLanguage.BrazilianPortuguese, AppLanguage.fromLocale(Locale.forLanguageTag("pt")))
        assertEquals(AppLanguage.EuropeanPortuguese, AppLanguage.fromLocale(Locale.forLanguageTag("pt-PT")))
        assertEquals(AppLanguage.Indonesian, AppLanguage.fromLocale(Locale("in")))
        assertEquals(AppLanguage.Filipino, AppLanguage.fromLocale(Locale.forLanguageTag("fil")))
        assertEquals(AppLanguage.Arabic, AppLanguage.fromLocale(Locale.forLanguageTag("ar-u-nu-latn")))
        assertEquals(AppLanguage.System, AppLanguage.fromLocale(null))
        assertEquals(Appearance.Dark, Appearance.fromWire("dark"))
        assertEquals(Appearance.System, Appearance.fromWire(null))
    }

    @Test
    fun aBlankLineStartsANewBlock() {
        val parsed = LegalDocumentParser.parse("Doc\nVersion 1.1 · Effective today\n\n## Contact\nfirst line\n\nsecond paragraph\nwrapped on", "Doc")
        assertEquals(listOf("Version 1.1 · Effective today", "Contact", "first line", "second paragraph wrapped on"), parsed.map { it.text })
        assertEquals(listOf(LegalBlock.Kind.Version, LegalBlock.Kind.Heading, LegalBlock.Kind.Paragraph, LegalBlock.Kind.Paragraph), parsed.map { it.kind })
        val bullets = LegalDocumentParser.parse("Doc\r\n- one\r\n  - two  \r\n\r\nend", "Doc")
        assertEquals(listOf(LegalBlock.Kind.Bullet, LegalBlock.Kind.Bullet, LegalBlock.Kind.Paragraph), bullets.map { it.kind })
        assertEquals(listOf("one", "two", "end"), bullets.map { it.text })
    }

    @Test
    fun englishFallbackSubstitutesSwiftSpecifiers() {
        assertEquals("Fingerprint 73C5DA0A", UiText.Ios("Fingerprint %@", listOf("73C5DA0A")).fallbackEnglish())
        assertEquals("Step 2 of 4", UiText.Ios("Step %lld of %lld", listOf(2, 4)).fallbackEnglish())
        assertEquals("4 of 2", UiText.Ios("%2\$lld of %1\$lld", listOf(2, 4)).fallbackEnglish())
        assertEquals("50%", UiText.Ios("%lld%%", listOf(50)).fallbackEnglish())
        assertEquals("plain", UiText.Ios("plain").fallbackEnglish())
        assertNull(java.util.concurrent.CancellationException().toUiText())
        assertEquals(UiText.Verbatim("Unsupported signing QR format"), IllegalStateException("Unsupported signing QR format").toUiText())
        assertEquals(UiText.Ios("Invalid signing plan."), AppIssue("Invalid signing plan.").toUiText())
    }
}
