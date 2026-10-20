package com.bittool.signer.core.engine.internal

import com.bittool.signer.core.engine.EngineException

/**
 * One QuickJS runtime with native entropy and the bundled engine installed — the Android counterpart of iOS
 * `EngineContext` (SigningEngine.swift). It evaluates only the verified engine bytecode; the runtime has no parser
 * (`eval`/`Function` are unavailable), no network, file system, timers or wallet storage interface. Must be created,
 * used and closed on [EngineThread].
 */
internal class QuickJsContextHost private constructor(private var handle: Long) : AutoCloseable {

    /** JSON is passed as an argument (UTF-8 bytes); request text is never interpolated into script. */
    fun call(requestJson: ByteArray): ByteArray {
        check(handle != 0L) { "runtime closed" }
        return try {
            NativeQuickJs.dispatch(handle, requestJson)
        } catch (t: Throwable) {
            throw EngineException(EngineException.ENGINE_FAILURE, "The offline engine could not process this request.")
        }
    }

    val memoryUsed: Long get() = if (handle == 0L) 0 else NativeQuickJs.memoryUsed(handle)

    override fun close() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        NativeQuickJs.freeRuntime(h)
    }

    companion object {
        const val MEMORY_LIMIT_BYTES = 512L * 1024 * 1024
        /** Must stay well below EngineThread's stack so deep recursion throws a catchable error instead of crashing. */
        const val MAX_STACK_BYTES = 8L * 1024 * 1024

        /** Creates a fresh runtime, installs entropy, evaluates the engine bytecode and binds `dispatch`. */
        fun create(bundle: EngineBundle): QuickJsContextHost {
            val handle = try {
                NativeQuickJs.newRuntime(MEMORY_LIMIT_BYTES, MAX_STACK_BYTES, compiler = false)
            } catch (t: Throwable) {
                0L
            }
            if (handle == 0L) {
                throw EngineException(EngineException.ENGINE_UNAVAILABLE, "The offline signing engine could not be started.")
            }
            try {
                try {
                    NativeQuickJs.evalBytecode(handle, bundle.bootstrap)
                } catch (t: Throwable) {
                    throw EngineException(EngineException.ENTROPY_UNAVAILABLE, "The engine’s secure entropy source could not be installed.")
                }
                try {
                    NativeQuickJs.evalBytecode(handle, bundle.engine)
                } catch (t: Throwable) {
                    throw EngineException(EngineException.ENGINE_INVALID, "The bundled signing engine could not be loaded.")
                }
                if (!NativeQuickJs.bindDispatch(handle)) {
                    throw EngineException(EngineException.ENGINE_INVALID, "The bundled signing engine could not be loaded.")
                }
                return QuickJsContextHost(handle)
            } catch (e: Throwable) {
                NativeQuickJs.freeRuntime(handle)
                throw e
            }
        }
    }
}
