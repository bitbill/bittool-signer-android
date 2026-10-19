package com.bittool.signer.core.engine.internal

import android.content.Context
import com.bittool.signer.core.engine.EngineException
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import java.security.MessageDigest
import java.util.Locale

/**
 * The bundled engine `assets/engine/signer.js`: byte-identical to the iOS resource, so a published build can be checked
 * with `shasum -a 256` against the same reproducible engine build. It is verified against [EXPECTED_SHA256] before use
 * and compiled once per process to QuickJS bytecode, which every fresh context then evaluates.
 */
internal class EngineBundle private constructor(val sha256: String, val bytes: Int, val bytecode: ByteArray) {
    companion object {
        const val ASSET_PATH = "engine/signer.js"
        /** SHA-256 of signer.js (engine/bundle-manifest.json of BitTool Signer 1.0.0). */
        const val EXPECTED_SHA256 = "6933d0d8a6f60faa8222506ac0bd7eef80eb884c9f73e31e9f354b4740f21077"

        /** Must run on the engine thread (compilation uses a throwaway QuickJS context). */
        fun load(context: Context): EngineBundle {
            val source = try {
                context.assets.open(ASSET_PATH).use { it.readBytes() }
            } catch (t: Throwable) {
                throw EngineException(EngineException.ENGINE_MISSING, "The bundled signing engine is unavailable. Reinstall BitTool Signer.")
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { String.format(Locale.ROOT, "%02x", it) }
            if (digest != EXPECTED_SHA256) {
                throw EngineException(EngineException.ENGINE_INVALID, "The bundled signing engine could not be loaded.")
            }
            try {
                QuickJSLoader.init()
            } catch (t: Throwable) {
                throw EngineException(EngineException.ENGINE_UNAVAILABLE, "The offline signing engine could not be started.")
            }
            val compiler = QuickJSContext.create()
            val bytecode = try {
                compiler.compile(jniSafeSource(String(source, Charsets.UTF_8)), "signer.js")
            } catch (t: Throwable) {
                throw EngineException(EngineException.ENGINE_INVALID, "The bundled signing engine could not be loaded.")
            } finally {
                runCatching { compiler.destroy() }
            }
            return EngineBundle(digest, source.size, bytecode)
        }

        /**
         * The QuickJS wrapper hands source to C through JNI "modified UTF-8", which writes U+0000 as `C0 80` and a
         * supplementary character as two 3-byte surrogates; QuickJS rejects both as invalid UTF-8. signer.js contains a
         * raw U+0000 inside a regular-expression class (`/[<NUL>-\x08…]/`). Each such character is rewritten as the
         * escape sequence with the same meaning in every place it can legally occur in the bundle — string, template
         * and regular-expression literals (`\x00`, and `\uD83D\uDE00` surrogate escapes, which a `u`-flag pattern
         * also reads as one code point) — or comments, where it is inert. An identity escape (`\<char>`) loses its
         * backslash first. The SHA-256 above is always checked on the bytes as shipped, before this step.
         */
        internal fun jniSafeSource(source: String): String {
            if (source.none { it == '\u0000' || it.isSurrogate() }) return source
            val out = StringBuilder(source.length + 64)
            var i = 0
            while (i < source.length) {
                val c = source[i]
                val escape: String? = when {
                    c == '\u0000' -> "\\x00"
                    c.isHighSurrogate() && i + 1 < source.length && source[i + 1].isLowSurrogate() ->
                        String.format(Locale.ROOT, "\\u%04X\\u%04X", c.code, source[i + 1].code)
                    c.isSurrogate() -> String.format(Locale.ROOT, "\\u%04X", c.code)
                    else -> null
                }
                if (escape == null) {
                    out.append(c)
                    i++
                    continue
                }
                var backslashes = 0
                while (backslashes < out.length && out[out.length - 1 - backslashes] == '\\') backslashes++
                if (backslashes % 2 == 1) out.setLength(out.length - 1)
                out.append(escape)
                i += if (c.isHighSurrogate() && escape.length > 6) 2 else 1
            }
            return out.toString()
        }
    }
}
