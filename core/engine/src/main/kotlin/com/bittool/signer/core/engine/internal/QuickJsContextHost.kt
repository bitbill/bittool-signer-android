package com.bittool.signer.core.engine.internal

import com.bittool.signer.core.engine.EngineException
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.QuickJSContext
import java.security.SecureRandom

/**
 * One QuickJS runtime + context with the bundled engine and native entropy installed — the Android counterpart of
 * iOS `EngineContext` (SigningEngine.swift). Only the verified bundled engine is evaluated; the context has no
 * network, file system, timers or wallet storage interface. Every QuickJS wrapper call must happen on the engine
 * thread that created the context ([EngineThread]).
 */
internal class QuickJsContextHost private constructor(
    private val context: QuickJSContext,
    private val dispatch: JSFunction,
) : AutoCloseable {

    private var closed = false

    /** JSON is passed as an argument; request text is never interpolated into script. */
    fun call(requestJson: String): String {
        check(!closed) { "context closed" }
        val response = try {
            dispatch.call(requestJson)
        } catch (t: Throwable) {
            throw EngineException(EngineException.ENGINE_FAILURE, "The offline engine could not process this request.")
        }
        return response as? String
            ?: throw EngineException(EngineException.ENGINE_FAILURE, "The offline engine could not process this request.")
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { dispatch.release() }
        runCatching { context.destroy() }
    }

    companion object {
        private const val MEMORY_LIMIT_BYTES = 512 * 1024 * 1024
        private const val MAX_STACK_BYTES = 8 * 1024 * 1024

        /**
         * Captures and removes the native entry point before the bundle is evaluated, then installs a frozen
         * `crypto.getRandomValues` (integer typed arrays only, at most 65536 bytes, like WebCrypto) and a `Math.random`
         * backed by the same entropy. Mirrors the iOS bootstrap byte for byte in behaviour; the only difference is that
         * the native function returns hex (QuickJS wrapper strings are the one lossless type both ways).
         */
        private const val BOOTSTRAP = """
(function (random) {
    'use strict';
    delete globalThis.__bitSignerRandom;
    var HEX = '0123456789abcdef';
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
            var hex = random(array.byteLength);
            if (typeof hex !== 'string' || hex.length !== array.byteLength * 2) throw new Error('Secure randomness unavailable');
            var out = new Uint8Array(array.buffer, array.byteOffset, array.byteLength);
            for (var i = 0; i < out.length; i++) {
                var hi = HEX.indexOf(hex.charAt(2 * i)), lo = HEX.indexOf(hex.charAt(2 * i + 1));
                if (hi < 0 || lo < 0) throw new Error('Secure randomness unavailable');
                out[i] = hi * 16 + lo;
            }
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

        private val secureRandom = SecureRandom()

        private val entropy = JSCallFunction { args ->
            val count = (args.firstOrNull() as? Number)?.toInt() ?: return@JSCallFunction null
            if (count !in 1..65536) return@JSCallFunction null
            val bytes = ByteArray(count)
            secureRandom.nextBytes(bytes)
            val hex = buildString(count * 2) {
                for (b in bytes) {
                    val v = b.toInt() and 0xff
                    append(HEX_DIGITS[v ushr 4]); append(HEX_DIGITS[v and 0x0f])
                }
            }
            bytes.fill(0)
            hex
        }

        private val HEX_DIGITS = "0123456789abcdef".toCharArray()

        /** Creates a fresh runtime, installs entropy, evaluates the engine bytecode and resolves `dispatch`. */
        fun create(bundle: EngineBundle): QuickJsContextHost {
            val context = try {
                QuickJSContext.create()
            } catch (t: Throwable) {
                throw EngineException(EngineException.ENGINE_UNAVAILABLE, "The offline signing engine could not be started.")
            }
            try {
                context.setMemoryLimit(MEMORY_LIMIT_BYTES)
                context.setMaxStackSize(MAX_STACK_BYTES)
                val global = context.globalObject
                global.setProperty("__bitSignerRandom", entropy)
                try {
                    context.evaluate(BOOTSTRAP, "bittool-bootstrap.js")
                } catch (t: Throwable) {
                    throw EngineException(EngineException.ENTROPY_UNAVAILABLE, "The engine’s secure entropy source could not be installed.")
                }
                try {
                    context.execute(bundle.bytecode)
                } catch (t: Throwable) {
                    throw EngineException(EngineException.ENGINE_INVALID, "The bundled signing engine could not be loaded.")
                }
                val engine = global.getJSObject("BitToolSignerEngine")
                    ?: throw EngineException(EngineException.ENGINE_INVALID, "The bundled signing engine could not be loaded.")
                val dispatch = engine.getJSFunction("dispatch")
                    ?: throw EngineException(EngineException.ENGINE_INVALID, "The bundled signing engine could not be loaded.")
                engine.release()
                return QuickJsContextHost(context, dispatch)
            } catch (e: Throwable) {
                runCatching { context.destroy() }
                throw e
            }
        }
    }
}
