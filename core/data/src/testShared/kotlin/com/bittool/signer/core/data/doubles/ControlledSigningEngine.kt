package com.bittool.signer.core.data.doubles

import com.bittool.signer.core.engine.BundleDigest
import com.bittool.signer.core.engine.EngineOperation
import com.bittool.signer.core.engine.SignerEngine
import com.bittool.signer.core.model.json.obj
import com.bittool.signer.core.model.json.str
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Exposes a race deterministically (iOS `EngineGate`, SessionTests.swift:467-480): the held operation reports that it
 * started and then waits until the test releases it.
 */
class EngineGate {
    private val started = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()

    suspend fun pause() {
        started.complete(Unit)
        released.await()
    }

    suspend fun waitUntilStarted() = started.await()

    fun release() {
        released.complete(Unit)
    }
}

/**
 * The port of iOS `ControlledSigningEngine` (SessionTests.swift:482-513) as a [SignerEngine] decorator: it counts
 * invocations per operation, can hold the next call of one operation behind an [EngineGate], and can replace the
 * resolver/signer/encoder with scripted orchestration tasks (multi-wallet plans that the reference fixtures cannot
 * produce for EVM) while every other operation — including `review` inside the scripted resolver — runs on [real].
 */
class ControlledSigningEngine(private val real: SignerEngine) : SignerEngine {
    private val invocations = ConcurrentHashMap<String, AtomicInteger>()
    private val pendingGate = AtomicReference<Pair<String, EngineGate>?>(null)
    private val orchestrationTasks = AtomicReference<List<JsonObject>?>(null)

    fun invocationCount(operation: EngineOperation): Int = invocationCount(operation.wireName)

    fun invocationCount(operation: String): Int = invocations[operation]?.get() ?: 0

    fun holdNext(operation: EngineOperation): EngineGate = holdNext(operation.wireName)

    /** `scanBegin` / `scanAdd` hold the scan session's calls. */
    fun holdNext(operation: String): EngineGate = EngineGate().also { pendingGate.set(operation to it) }

    fun useOrchestrationTasks(tasks: List<JsonObject>) {
        orchestrationTasks.set(tasks)
    }

    override suspend fun run(operation: EngineOperation, parameters: JsonObject): JsonObject {
        enter(operation.wireName)
        val tasks = orchestrationTasks.get()
        if (tasks != null) {
            when (operation) {
                EngineOperation.ResolveSigningPlan -> {
                    val reviewed = real.run(EngineOperation.Review, JsonObject(mapOf("request" to (parameters["request"] ?: JsonObject(emptyMap())))))
                    return JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("ready"),
                            "requestHash" to JsonPrimitive(reviewed.str("requestHash") ?: ""),
                            "planHash" to JsonPrimitive("a".repeat(64)),
                            "tasks" to JsonArray(tasks),
                        ),
                    )
                }
                EngineOperation.SignPlanTask -> {
                    val plan = parameters.obj("plan") ?: JsonObject(emptyMap())
                    return JsonObject(
                        mapOf(
                            "requestHash" to JsonPrimitive(plan.str("requestHash") ?: ""),
                            "planHash" to JsonPrimitive(plan.str("planHash") ?: ""),
                            "taskId" to JsonPrimitive(parameters.str("taskId") ?: ""),
                            "signature" to JsonPrimitive("public-orchestration-fixture"),
                        ),
                    )
                }
                EngineOperation.EncodeResponse -> return JsonObject(
                    mapOf(
                        "frames" to JsonArray(listOf(JsonPrimitive("public-orchestration-result"))),
                        "text" to JsonPrimitive("public-orchestration-result"),
                    ),
                )
                else -> Unit
            }
        }
        return real.run(operation, parameters)
    }

    override suspend fun beginScanSession() {
        enter("scanBegin")
        real.beginScanSession()
    }

    override suspend fun scan(frame: String): JsonObject {
        enter("scanAdd")
        return real.scan(frame)
    }

    override suspend fun endScanSession() = real.endScanSession()

    override suspend fun releaseWarmContext() {
        invocations.getOrPut("releaseWarmContext") { AtomicInteger() }.incrementAndGet()
        real.releaseWarmContext()
    }

    override suspend fun prewarm() = real.prewarm()

    override suspend fun bundleDigest(): BundleDigest = real.bundleDigest()

    private suspend fun enter(operation: String) {
        invocations.getOrPut(operation) { AtomicInteger() }.incrementAndGet()
        val held = pendingGate.get()
        if (held != null && held.first == operation && pendingGate.compareAndSet(held, null)) held.second.pause()
    }
}
