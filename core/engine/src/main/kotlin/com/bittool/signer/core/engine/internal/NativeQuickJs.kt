package com.bittool.signer.core.engine.internal

/**
 * JNI surface of `libbittoolsigner_engine.so` (src/main/cpp/signer_jni.c, vendored QuickJS). Text crosses as UTF-8
 * byte arrays. Every call for a handle must happen on the thread that created it ([EngineThread]).
 */
internal object NativeQuickJs {
    init {
        System.loadLibrary("bittoolsigner_engine")
    }

    /** Returns 0 when the runtime cannot be created. `compiler` runtimes keep the parser; engine runtimes do not. */
    @JvmStatic external fun newRuntime(memoryLimit: Long, maxStack: Long, compiler: Boolean): Long
    @JvmStatic external fun freeRuntime(handle: Long)
    @JvmStatic external fun compile(handle: Long, source: ByteArray, fileName: String): ByteArray
    @JvmStatic external fun evalBytecode(handle: Long, bytecode: ByteArray)
    @JvmStatic external fun bindDispatch(handle: Long): Boolean
    @JvmStatic external fun dispatch(handle: Long, request: ByteArray): ByteArray
    @JvmStatic external fun memoryUsed(handle: Long): Long
    @JvmStatic external fun version(): String
}

/** A native failure (JS exception, bytecode or allocation problem). Its message is for debug logs only. */
internal class NativeEngineException(message: String) : RuntimeException(message)
