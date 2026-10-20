package com.bittool.signer.core.data.reference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.model.json.SignerJson
import com.bittool.signer.core.model.registry.TokenRegistryEntry
import com.bittool.signer.core.model.util.Hex
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** iOS TokenRegistryBundleTests (the three icon cases) over [PackTokenIconSource]. */
@RunWith(RobolectricTestRunner::class)
class TokenIconPackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Serializable
    private data class RegistryFile(val assets: List<TokenRegistryEntry>)

    private val assets: List<TokenRegistryEntry> by lazy {
        val text = context.assets.open(AssetReferenceDataRepository.TOKEN_REGISTRY).use { it.readBytes() }.decodeToString()
        SignerJson.decodeFromString(RegistryFile.serializer(), text).assets
    }

    private fun source(scope: kotlinx.coroutines.test.TestScope) = PackTokenIconSource(context, StandardTestDispatcher(scope.testScheduler))

    private fun sha256(bytes: ByteArray) = Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Width and height from a PNG's IHDR chunk, after checking the signature. */
    private fun pngSize(bytes: ByteArray): Pair<Int, Int> {
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(bytes.copyOfRange(0, 8).contentEquals(signature), "PNG signature")
        assertEquals("IHDR", bytes.copyOfRange(12, 16).decodeToString())
        fun int(at: Int) = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (bytes[at + i].toInt() and 0xFF) }
        return int(16) to int(20)
    }

    @Test
    fun everyTokenIconResolvesToItsOwnArtwork() = runTest {
        val source = source(this)
        assertTrue(source.count > 1_500)
        val ids = assets.map { it.icon }.toSet()
        assertEquals(ids, source.ids, "the pack must hold exactly the icons the registry uses")
        for (icon in ids.take(50)) {
            val png = assertNotNull(source.png(icon), icon)
            val (width, height) = pngSize(png)
            assertTrue(width in 1..128 && height in 1..128, "$icon is $width×$height")
        }
    }

    @Test
    fun iconsAreAddressedByTheirContentAndSharedBetweenChains() = runTest {
        val usdt = assets.first { it.chainId == "1" && it.symbol == "USDT" }
        val tronUsdt = assets.first { it.chainFamily == "tron" && it.symbol == "USDT" }
        assertEquals(usdt.icon, tronUsdt.icon, "one image serves the same token on every chain")
        val source = source(this)
        assertTrue(sha256(assertNotNull(source.png(usdt.icon))).startsWith(usdt.icon))
        // Every entry of the pack, not only the sampled ones.
        for (icon in source.ids) assertTrue(sha256(assertNotNull(source.slice(icon))).startsWith(icon), icon)
    }

    @Test
    fun unknownAndEmptyIconsHaveNoImage() = runTest {
        val source = source(this)
        assertNull(source.png(""))
        assertNull(source.png("0000000000000000"))
        assertNull(source.png("../../etc/passwd"))
        assertNull(source.png("373B13FEFBB58245"), "IDs are exact lowercase keys")
    }
}
