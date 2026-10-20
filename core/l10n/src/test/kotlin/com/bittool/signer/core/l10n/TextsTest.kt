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
}
