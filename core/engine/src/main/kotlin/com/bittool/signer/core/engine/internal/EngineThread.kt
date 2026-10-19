package com.bittool.signer.core.engine.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The single thread every QuickJS call runs on (the wrapper has thread affinity), with a large stack: the engine's
 * CBOR/PSBT decoders recurse and QuickJS's own stack limit is raised to match. One thread also serialises all
 * operations, like the iOS `actor SigningEngine`.
 */
internal object EngineThread {
    private const val STACK_BYTES = 32L * 1024 * 1024

    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "bittool-engine", STACK_BYTES).apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
