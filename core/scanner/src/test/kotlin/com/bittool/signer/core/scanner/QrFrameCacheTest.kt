package com.bittool.signer.core.scanner

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bittool.signer.core.scanner.testing.urFrame
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [QrFrameCache] (QA audit A13): one encode per animated code and screen, bounded, gone with the screen's store. */
@RunWith(RobolectricTestRunner::class)
class QrFrameCacheTest {
    private val store = ViewModelStore()
    private val cache = ViewModelProvider.create(store, viewModelFactory { initializer { QrFrameCache() } })
        .get(QrFrameCache::class)

    private fun code(parts: Int, size: Int) = (1..parts).map { urFrame(it, parts, size) }

    @Test
    fun aCodeIsEncodedOnceAndKeepsThePartItReached(): Unit = runBlocking {
        val frames = code(3, 120)
        val encodesBefore = QrFrames.renderCalls.get()
        val first = assertNotNull(cache.render(frames))
        assertEquals(3, first.size)
        cache.park(frames, index = 2)
        assertEquals(first, cache.render(frames))
        assertEquals(first, assertNotNull(cache.ready(frames)).images)
        assertEquals(2, cache.index(frames))
        assertEquals(encodesBefore + 1, QrFrames.renderCalls.get())
    }

    @Test
    fun aCodeShownFromPreparedMatricesKeepsOnlyItsPart(): Unit = runBlocking {
        val frames = code(3, 125)
        val encodesBefore = QrFrames.renderCalls.get()
        cache.park(frames, index = 1)
        assertNull(cache.ready(frames), "Nothing was encoded here")
        assertEquals(1, cache.index(frames))
        assertEquals(encodesBefore, QrFrames.renderCalls.get())
        // Shown later without its matrices: encoded then, the part kept.
        assertEquals(3, assertNotNull(cache.render(frames)).size)
        assertEquals(1, cache.index(frames))
    }

    @Test
    fun aDroppedCodeIsForgotten(): Unit = runBlocking {
        val frames = code(3, 130)
        cache.render(frames)
        cache.park(frames, index = 1)
        cache.drop(frames)
        assertNull(cache.ready(frames))
        assertEquals(0, cache.index(frames))
    }

    @Test
    fun aScreenKeepsAtMostTwoCodesTheLeastRecentlyUsedGoingFirst(): Unit = runBlocking {
        val codes = listOf(code(2, 120), code(2, 140), code(2, 160))
        assertEquals(2, QrFrameCache.MAX_ENTRIES)
        cache.render(codes[0])
        cache.render(codes[1])
        cache.render(codes[0]) // shown again: now the most recent
        cache.render(codes[2])
        assertNotNull(cache.ready(codes[0]))
        assertNull(cache.ready(codes[1]))
        assertNotNull(cache.ready(codes[2]))
    }

    @Test
    fun everythingGoesWithTheScreensStore(): Unit = runBlocking {
        val frames = code(3, 150)
        cache.render(frames)
        store.clear()
        assertNull(cache.ready(frames))
        assertNull(cache.render(frames), "A cleared cache leaves the encode to the caller")
    }
}
