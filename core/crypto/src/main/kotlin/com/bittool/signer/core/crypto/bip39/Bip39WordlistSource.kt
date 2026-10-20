package com.bittool.signer.core.crypto.bip39

import android.content.res.AssetManager
import java.io.File
import java.io.IOException

/**
 * Where [DefaultBip39] reads the raw wordlist files (gaps.md G8: assets `bip39/bip39-<fileName>.txt`). The bytes are
 * verified against the pinned SHA-256 after reading, so a source cannot substitute a list.
 */
fun interface Bip39WordlistSource {
    /** The file bytes of [language], or null when it cannot be read (the list is then unavailable). */
    fun read(language: Bip39Language): ByteArray?

    companion object {
        /** Each wordlist file is 13–33 KB; anything larger is not a wordlist. */
        internal const val MAX_FILE_BYTES = 64 * 1024

        /** The APK assets (production). */
        fun assets(assets: AssetManager): Bip39WordlistSource = Bip39WordlistSource { language ->
            try {
                assets.open(language.assetPath).use { input ->
                    val bytes = input.readNBytesCompat(MAX_FILE_BYTES + 1)
                    bytes.takeIf { it.size <= MAX_FILE_BYTES }
                }
            } catch (_: IOException) {
                null
            }
        }

        /** A directory laid out like the assets folder (JVM unit tests read `src/main/assets`). */
        fun directory(root: File): Bip39WordlistSource = Bip39WordlistSource { language ->
            val file = File(root, language.assetPath)
            try {
                if (file.isFile && file.length() <= MAX_FILE_BYTES) file.readBytes() else null
            } catch (_: IOException) {
                null
            }
        }

        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() < limit) {
                val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
