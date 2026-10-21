package com.bittool.signer.core.l10n

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.review.ReviewText
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class TextsTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun englishResolvesCatalogueKeysAndFallsBack() {
        assertEquals("Wallets", UiText.Ios("Wallets").resolve(resources))
        assertEquals("Step 2 of 4", UiText.Ios("Step %lld of %lld", listOf(2, 4)).resolve(resources))
        assertEquals("Not in the catalogue 7", UiText.Ios("Not in the catalogue %lld", listOf(7)).resolve(resources))
        assertEquals("verbatim", UiText.Verbatim("verbatim").resolve(resources))
        assertEquals(
            "Set a screen lock (PIN, pattern or password) to create or use a wallet. The device key requires screen lock protection.",
            UiText.Resource(R.string.crypto_device_lock_required).resolve(resources),
        )
    }

    @Test
    @Config(qualifiers = "b+zh+Hans")
    fun simplifiedChineseTranslatesCatalogueAndEngineText() {
        assertEquals("钱包", UiText.Ios("Wallets").resolve(resources))
        assertEquals("第 2 步，共 4 步", UiText.Ios("Step %lld of %lld", listOf(2, 4)).resolve(resources))
        val review: ReviewText = resources.reviewText()
        assertEquals("方法 0x3593564c", review.headline("Method 0x3593564c"))
        assertEquals("金额 (USDC)", review.label("Amount (USDC)"))
        assertEquals("Dilithium Vault Rebalance", review.phrase("Dilithium Vault Rebalance"))
    }

    /**
     * The strings iOS ff2756f / f9092a7 stopped drawing verbatim: the scan status, the weak recovery-cards warning, the
     * App Lock button and the resolver's matching messages (through `ReviewText.phrase`), with Android specifiers.
     */
    @Test
    fun theFormerlyVerbatimIosStringsFormatInEnglish() {
        assertEquals("Reading request…", resources.getString(R.string.reading_request))
        assertEquals("Keep the animated code in view · 3 parts", resources.getString(R.string.keep_the_animated_code_in_view_x_parts, 3))
        assertEquals("Unlock with Biometrics", resources.getString(R.string.unlock_with_x, resources.getString(R.string.security_biometrics_name)))
        assertEquals(
            "With a 12-word phrase each card shows 8 of the 12 words, and the other 4 could be worked out with enough " +
                "computing power. Guard every card as carefully as the full phrase, or use a 24-word phrase for split backups.",
            resources.getString(R.string.with_a_x_word_phrase_each_card_shows_x_of_the_x_words, 12, 8, 12, 4),
        )
        val review = resources.reviewText()
        for (message in listOf("No matching wallet", "Missing signer information", "Ambiguous wallet match", "Wallet setup required", "Invalid signing request")) {
            assertEquals(message, review.phrase(message))
        }
        assertEquals("Wallet setup required.", review.phrase("Wallet setup required."))
    }

    /** Chinese reads the warning's values 1, 3, 2, 4: the positional specifiers keep every number in its place. */
    @Test
    @Config(qualifiers = "b+zh+Hans")
    fun simplifiedChineseKeepsTheWarningsNumbersInPlace() {
        assertEquals(
            "12 词的助记词，每张卡片会显示 12 个词中的 8 个，剩下的 4 个有足够算力就能推算出来。请像保管完整助记词一样保管每一张卡片，或改用 24 词助记词来做分卡备份。",
            resources.getString(R.string.with_a_x_word_phrase_each_card_shows_x_of_the_x_words, 12, 8, 12, 4),
        )
        assertEquals("保持动态二维码在取景框内 · 已读取 3 段", resources.getString(R.string.keep_the_animated_code_in_view_x_parts, 3))
        assertEquals("正在读取请求…", resources.getString(R.string.reading_request))
        val review = resources.reviewText()
        assertEquals("没有匹配的钱包", review.phrase("No matching wallet"))
        assertEquals("签名请求无效", review.phrase("Invalid signing request"))
        assertEquals("钱包需要先完成设置", review.phrase("Wallet setup required"))
    }

    /** Turkish also reorders the warning (1, 3, 2, 4), and its button starts with the unlock method. */
    @Test
    @Config(qualifiers = "tr")
    fun turkishKeepsTheWarningsNumbersInPlace() {
        assertEquals(
            "15 kelimelik bir ifadede her kart 15 kelimenin 10 tanesini gösterir, kalan 5 kelime ise yeterli işlem gücüyle " +
                "bulunabilir. Her kartı tam ifade kadar dikkatli saklayın ya da bölünmüş yedekler için 24 kelimelik bir ifade kullanın.",
            resources.getString(R.string.with_a_x_word_phrase_each_card_shows_x_of_the_x_words, 15, 10, 15, 5),
        )
        assertEquals("Biyometri ile kilidi aç", resources.getString(R.string.unlock_with_x, resources.getString(R.string.security_biometrics_name)))
    }
}
