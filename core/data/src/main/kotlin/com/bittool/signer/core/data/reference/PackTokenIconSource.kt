package com.bittool.signer.core.data.reference

import android.content.Context
import com.bittool.signer.core.common.coroutines.IoDispatcher
import com.bittool.signer.core.common.log.Log
import com.bittool.signer.core.model.TokenIconSource
import com.bittool.signer.core.model.json.asLong
import com.bittool.signer.core.model.json.parseJsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray

/**
 * The bundled token artwork (port of iOS `Services/TokenIconStore.swift`, models-state.md §11, activity-settings.md §5).
 *
 * Every icon lives in one file, `assets/registry/TokenIcons.pack` (concatenated PNGs), addressed by
 * `assets/registry/TokenIcons.index.json` = `{"<first 16 hex of the PNG's SHA-256>": [offset, length]}`; tokens that
 * share artwork share one entry. The pack is stored uncompressed (`noCompress += "pack"` in `:app` and here) and
 * memory-mapped, so only the pages an icon occupies are read (iOS `.mappedIfSafe`). If the platform cannot hand out a
 * descriptor for it (a compressed asset, a test runtime) it is read into memory once instead. The index is parsed
 * lazily, once.
 *
 * [png] returns the PNG bytes or null for an empty, unknown or malformed ID (`"../../etc/passwd"` is simply not a key)
 * and for an entry whose range is not inside the pack. Decoding and the 200-entry bitmap cache belong to `:core:ui`'s
 * `TokenIcon`. Nothing here touches the network.
 */
@Singleton
class PackTokenIconSource @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : TokenIconSource {

    private val assets = context.assets

    private val index: Map<String, List<Long>> by lazy { parseIndex() }
    private val pack: ByteBuffer? by lazy { mapPack() }

    /** The number of icons in the index (iOS `TokenIconStore.count`). */
    val count: Int get() = index.size

    /** Every icon ID of the index. */
    val ids: Set<String> get() = index.keys

    override suspend fun png(iconId: String): ByteArray? = if (iconId.isEmpty()) null else withContext(io) { slice(iconId) }

    /** iOS `image(_:)` (`TokenIconStore.swift:32-44`) without the decode: the bytes of [iconId], or null. */
    internal fun slice(iconId: String): ByteArray? {
        val range = index[iconId] ?: return null
        if (range.size != 2) return null
        val (offset, length) = range
        val buffer = pack ?: return null
        if (offset < 0 || length <= 0 || offset + length > buffer.capacity()) return null
        val out = ByteArray(length.toInt())
        // A duplicate has its own position, so concurrent reads never interfere.
        buffer.duplicate().apply { position(offset.toInt()) }.get(out)
        return out
    }

    private fun parseIndex(): Map<String, List<Long>> {
        val root = try {
            assets.open(INDEX).use { it.readBytes() }.let(::parseJsonObject)
        } catch (e: IOException) {
            null
        } ?: return emptyMap()
        // iOS decodes `[String: [Int]]`: one entry of another shape makes the whole index unusable.
        return root.mapValues { (_, value) ->
            val array = value as? JsonArray ?: return emptyMap()
            array.map { it.asLong() ?: return emptyMap() }
        }
    }

    private fun mapPack(): ByteBuffer? = try {
        assets.openFd(PACK).use { descriptor ->
            // The stream does not own the descriptor (it is closed with `descriptor`); the mapping outlives both.
            FileInputStream(descriptor.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length)
        }
    } catch (e: IOException) {
        Log.w(TAG) { "Token icon pack is not mappable; reading it into memory" }
        try {
            ByteBuffer.wrap(assets.open(PACK).use { it.readBytes() }).asReadOnlyBuffer()
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        const val PACK = "registry/TokenIcons.pack"
        const val INDEX = "registry/TokenIcons.index.json"
        private const val TAG = "TokenIcons"
    }
}
