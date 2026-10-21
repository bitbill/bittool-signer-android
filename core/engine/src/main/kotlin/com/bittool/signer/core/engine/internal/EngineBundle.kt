package com.bittool.signer.core.engine.internal

import android.content.Context
import com.bittool.signer.core.engine.EngineException
import java.security.MessageDigest
import java.util.Locale

/**
 * The bundled engine `assets/engine/signer.js`: byte-identical to the iOS resource, so a published build can be checked
 * with `shasum -a 256` against the same reproducible engine build. It is verified against [EXPECTED_SHA256] before use
 * and compiled once per process, in memory, to QuickJS bytecode that every fresh runtime then evaluates. Bytecode is
 * never written to or read from storage.
 */
internal class EngineBundle private constructor(
    val sha256: String,
    val bytes: Int,
    val bootstrap: ByteArray,
    val engine: ByteArray,
) {
    companion object {
        const val ASSET_PATH = "engine/signer.js"
        /** SHA-256 of signer.js (engine/bundle-manifest.json of BitTool Signer 1.0.0). */
        const val EXPECTED_SHA256 = "6933d0d8a6f60faa8222506ac0bd7eef80eb884c9f73e31e9f354b4740f21077"
        const val EXPECTED_BYTES = 1_181_026

        /**
         * Captures and removes the native entropy function before the bundle runs, then installs a frozen
         * `crypto.getRandomValues` (integer typed arrays only, at most 65536 bytes, like WebCrypto) and a `Math.random`
         * backed by the same entropy — the iOS bootstrap (SigningEngine.swift) with an ArrayBuffer-returning source.
         */
        const val BOOTSTRAP = """
(function (random) {
    'use strict';
    delete globalThis.__bitSignerRandom;
    Object.defineProperty(globalThis, 'crypto', {value: Object.freeze({
        getRandomValues: function (array) {
            var integer = array instanceof Int8Array || array instanceof Uint8Array ||
                array instanceof Uint8ClampedArray || array instanceof Int16Array ||
                array instanceof Uint16Array || array instanceof Int32Array ||
                array instanceof Uint32Array ||
                (typeof BigInt64Array !== 'undefined' && array instanceof BigInt64Array) ||
                (typeof BigUint64Array !== 'undefined' && array instanceof BigUint64Array);
            if (!integer || array.byteLength > 65536) throw new Error('Invalid entropy request');
            if (array.byteLength === 0) return array;
            var bytes = random(array.byteLength);
            if (!(bytes instanceof ArrayBuffer) || bytes.byteLength !== array.byteLength) throw new Error('Secure randomness unavailable');
            new Uint8Array(array.buffer, array.byteOffset, array.byteLength).set(new Uint8Array(bytes));
            return array;
        }
    }), writable: false, configurable: false});
    Object.defineProperty(Math, 'random', {value: function () {
        var bytes = new Uint32Array(2);
        crypto.getRandomValues(bytes);
        return ((bytes[0] >>> 5) * 67108864 + (bytes[1] >>> 6)) / 9007199254740992;
    }, writable: false, configurable: false});
})(__bitSignerRandom);
"""

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

        /** Must run on the engine thread (compilation uses a throwaway compiler runtime). */
        fun load(context: Context): EngineBundle {
            val source = try {
                context.assets.open(ASSET_PATH).use { it.readBytes() }
            } catch (t: Throwable) {
                throw EngineException(EngineException.ENGINE_MISSING, "The bundled signing engine is unavailable. Reinstall BitTool Signer.")
            }
            val digest = sha256Hex(source)
            if (digest != EXPECTED_SHA256 || source.size != EXPECTED_BYTES) {
                throw EngineException(EngineException.ENGINE_INVALID, "The bundled signing engine could not be loaded.")
            }
            val (bootstrap, engine) = compile("bittool-bootstrap.js" to BOOTSTRAP.toByteArray(Charsets.UTF_8), "signer.js" to source)
            return EngineBundle(digest, source.size, bootstrap, engine)
        }

        /**
         * Compiles UTF-8 JavaScript [sources] (file name to bytes) to bytecode in one throwaway compiler runtime, the
         * only kind of runtime with a parser. Must run on the engine thread. The isolation tests compile their probes
         * with it.
         */
        fun compile(vararg sources: Pair<String, ByteArray>): List<ByteArray> {
            val compiler = try {
                NativeQuickJs.newRuntime(QuickJsContextHost.MEMORY_LIMIT_BYTES, QuickJsContextHost.MAX_STACK_BYTES, compiler = true)
            } catch (t: Throwable) {
                0L
            }
            if (compiler == 0L) {
                throw EngineException(EngineException.ENGINE_UNAVAILABLE, "The offline signing engine could not be started.")
            }
            try {
                return sources.map { (name, bytes) -> NativeQuickJs.compile(compiler, bytes, name) }
            } catch (t: Throwable) {
                throw EngineException(EngineException.ENGINE_INVALID, "The bundled signing engine could not be loaded.")
            } finally {
                NativeQuickJs.freeRuntime(compiler)
            }
        }
    }
}
