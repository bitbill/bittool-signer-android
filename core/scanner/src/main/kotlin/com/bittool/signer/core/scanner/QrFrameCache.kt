package com.bittool.signer.core.scanner

import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The encoded frames of the **animated** codes one screen shows (QA audit A13), kept by that screen's
 * `ViewModelStore` (its navigation entry), so a code that comes back after a tab switch, a push and pop or a
 * configuration change is not encoded again from frame 1 and continues at the part it had reached: iOS keeps
 * `QRCodeView`'s `@State` across `TabView` switches and skips the work (`guard renderedFrames != frames`,
 * QRCodeView.swift:97).
 *
 * - Only animated codes are kept. They are public (pairing, signatures) and costly (hundreds of parts); a one-frame code
 *   is cheap to encode and may be secret (the recovery-phrase QR), so it is never kept once it leaves.
 * - An entry is dropped when its code leaves while the screen is resumed (the code itself went away: Done, a new
 *   request, another format, a closed sheet), so frames are never kept across screens; it is parked when the code leaves
 *   because the screen did (lifecycle below RESUMED: a tab switch, a push, the background, a configuration change).
 * - The encode runs in [viewModelScope] on `Dispatchers.Default`: one that a tab switch interrupts goes on and is
 *   awaited by the code that comes back, instead of a second encode starting beside it. Dropping an entry cancels it.
 *   A code shown from prepared matrices (`initial`) keeps only its part here.
 * - At most [MAX_ENTRIES] entries per screen, the least recently used going first; everything goes with the screen's
 *   store (the navigation entry popped, the activity finished).
 *
 * Main thread only (composition and its effects).
 */
@MainThread
internal class QrFrameCache : ViewModel() {
    /** [images] is null for a code shown from prepared matrices (`initial`): only its part is kept. */
    private class Entry {
        var images: Deferred<List<ImageBitmap>>? = null
        var index = 0
    }

    /** Least recently used first (access order), so the code on screen is the last one to go. */
    private val entries = LinkedHashMap<List<String>, Entry>(4, 0.75f, true)
    private var cleared = false

    /** The images already encoded for exactly [frames], or null (none, or the encode is still running). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun ready(frames: List<String>): RenderedQr? {
        val job = entries[frames]?.images ?: return null
        if (!job.isCompleted || job.isCancelled) return null
        return RenderedQr(frames, job.getCompleted())
    }

    /** The part a parked code had reached (0 when there is none). */
    fun index(frames: List<String>): Int = entries[frames]?.index ?: 0

    /**
     * The images of [frames], encoded once per entry. Null when this cache can no longer provide them (cleared, or the
     * entry was dropped or evicted meanwhile); the caller then encodes itself. Throws only when the caller is cancelled.
     */
    suspend fun render(frames: List<String>): List<ImageBitmap>? {
        if (cleared) return null
        val entry = entries[frames] ?: Entry().also { put(frames, it) }
        val images = entry.images
            ?: viewModelScope.async(Dispatchers.Default) { QrFrames.render(frames) }.also { entry.images = it }
        return try {
            images.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        }
    }

    /** The code left with its screen: keep its images (if encoded here) and the part it showed for its return. */
    fun park(frames: List<String>, index: Int) {
        if (cleared) return
        (entries[frames] ?: Entry().also { put(frames, it) }).index = index
    }

    /** The code left the screen: forget it, and stop its encode if it is still running. */
    fun drop(frames: List<String>) {
        entries.remove(frames)?.images?.cancel()
    }

    private fun put(frames: List<String>, entry: Entry) {
        entries[frames] = entry
        while (entries.size > MAX_ENTRIES) {
            val eldest = entries.keys.first()
            entries.remove(eldest)?.images?.cancel()
        }
    }

    override fun onCleared() {
        cleared = true
        entries.values.forEach { it.images?.cancel() }
        entries.clear()
    }

    companion object {
        /** Animated codes one screen keeps at most (a screen shows one at a time; the least recently used goes). */
        const val MAX_ENTRIES = 2

        private const val KEY = "com.bittool.signer.core.scanner.QrFrameCache"

        private val factory = viewModelFactory { initializer { QrFrameCache() } }

        /**
         * The cache of the screen this composition belongs to (`LocalViewModelStoreOwner`: its navigation entry, else
         * the activity), or null without one. Looked up once per owner: a code composed while its entry leaves (an exit
         * transition) must not ask a destroyed entry for its store again on every part.
         */
        @Composable
        fun forCurrentScreen(): QrFrameCache? {
            val owner = LocalViewModelStoreOwner.current ?: return null
            return remember(owner) {
                try {
                    ViewModelProvider(owner, factory)[KEY, QrFrameCache::class.java]
                } catch (e: IllegalStateException) {
                    null // the entry is already destroyed: encode without a cache
                }
            }
        }
    }
}
