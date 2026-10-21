package com.bittool.signer.core.engine.internal

import android.content.ContextWrapper
import com.bittool.signer.core.engine.EngineException
import com.bittool.signer.core.engine.EngineOperation
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * QA audit A07/A12: the request JSON (key sorting, encoding and the 1 MiB check over up to a megabyte of PSBT or
 * EIP-712 data) is built on the engine thread, never on the caller's, which is the main thread for review, plan
 * resolution and signing. Both requests here are over the limit, so no native runtime is needed.
 */
class EngineRequestThreadTest {
    private val engine = QuickJsSignerEngine(ContextWrapper(null), allowTestOperations = true)
    private val oversized = "x".repeat(QuickJsSignerEngine.MAX_REQUEST_BYTES)

    /** Parameters that record the thread of every read (sorting, encoding and sizing all read the entries). */
    private class RecordingParameters(private val content: Map<String, JsonElement>) : AbstractMap<String, JsonElement>() {
        val readers: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

        override val entries: Set<Map.Entry<String, JsonElement>>
            get() {
                // Without the " @coroutine#n" suffix that coroutine debug mode adds to thread names.
                readers += Thread.currentThread().name.substringBefore(" @")
                return content.entries
            }
    }

    @Test
    fun aRequestIsBuiltAndSizeCheckedOnTheEngineThread() = runBlocking {
        val parameters = RecordingParameters(mapOf("payload" to JsonPrimitive(oversized)))
        val refused = assertFailsWith<EngineException> { engine.run(EngineOperation.DecodePayload, JsonObject(parameters)) }
        assertEquals(EngineException.REQUEST_TOO_LARGE, refused.code)
        assertEquals(setOf("bittool-engine"), parameters.readers.toSet())
    }

    @Test
    fun aScannedFrameIsEncodedOnTheEngineThreadToo() = runBlocking {
        // While the engine thread is busy, a scan call must wait for it instead of encoding its frame right away.
        val busy = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = launch(Dispatchers.IO) {
            withContext(EngineThread.dispatcher) {
                busy.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        busy.await(30, TimeUnit.SECONDS)
        val scan = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            assertFailsWith<EngineException> { engine.scan(oversized) }
        }
        delay(300)
        assertFalse(scan.isCompleted, "the frame was encoded on the caller's thread")
        release.countDown()
        assertEquals(EngineException.REQUEST_TOO_LARGE, scan.await().code)
        blocker.join()
    }
}
