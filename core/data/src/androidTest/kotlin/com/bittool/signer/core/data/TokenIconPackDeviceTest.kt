package com.bittool.signer.core.data

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.data.reference.PackTokenIconSource
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** The token icon pack is stored uncompressed and memory-mapped on the device, and its entries decode as images. */
@RunWith(AndroidJUnit4::class)
class TokenIconPackDeviceTest {
    @Test
    fun thePackIsMappableAndEveryIconDecodes() = runBlocking<Unit> {
        DeviceEngine.context.assets.openFd(PackTokenIconSource.PACK).use { descriptor ->
            assertTrue(descriptor.length > 11_000_000, "noCompress keeps the pack uncompressed, so it has a descriptor.")
        }
        val source = PackTokenIconSource(DeviceEngine.context, Dispatchers.IO)
        assertTrue(source.count > 1_500)
        for (icon in source.ids) {
            val png = assertNotNull(source.png(icon), icon)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, options)
            assertTrue(options.outWidth in 1..128 && options.outHeight in 1..128, "$icon is ${options.outWidth}×${options.outHeight}")
        }
        val sample = source.png(source.ids.first())!!
        assertNotNull(BitmapFactory.decodeByteArray(sample, 0, sample.size))
        assertNull(source.png("../../etc/passwd"))
    }
}
