package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.crypto.vault.DeviceKeyStore
import com.bittool.signer.core.crypto.vault.PublicDataKey
import com.bittool.signer.core.model.WalletId
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [DeviceKeyStore] over [delegate] that can hold one call on a latch, so a test can cancel the calling coroutine while
 * the vault is inside a given step (a precondition, a commit), and that records the thread of every call (Keystore and
 * `KeyguardManager` are binder IPC and must never run on the caller's — possibly the main — thread).
 */
class GatedDeviceKeyStore(val delegate: InMemoryDeviceKeyStore = InMemoryDeviceKeyStore()) : DeviceKeyStore {
    /** Holds the [occurrence]-th call of [operation] (counted from [hold]) until [release]. */
    class Gate(val operation: String, val occurrence: Int) {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun awaitEntered() = check(entered.await(30, TimeUnit.SECONDS)) { "the vault never reached $operation #$occurrence" }

        fun release() = released.countDown()

        internal fun pass() {
            entered.countDown()
            check(released.await(30, TimeUnit.SECONDS)) { "$operation #$occurrence was never released" }
        }
    }

    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile private var gate: Gate? = null

    /** The thread of every call, in order. */
    val threads: MutableList<Thread> = Collections.synchronizedList(ArrayList())

    fun hold(operation: String, occurrence: Int = 1): Gate {
        counts.clear()
        return Gate(operation, occurrence).also { gate = it }
    }

    private fun enter(operation: String) {
        threads += Thread.currentThread()
        val n = counts.getOrPut(operation) { AtomicInteger() }.incrementAndGet()
        val current = gate ?: return
        if (current.operation == operation && current.occurrence == n) {
            gate = null
            current.pass()
        }
    }

    override fun isDeviceSecure(): Boolean {
        enter("isDeviceSecure")
        return delegate.isDeviceSecure()
    }

    override fun create(id: WalletId) {
        enter("create")
        delegate.create(id)
    }

    override fun seal(id: WalletId, plaintext: ByteArray, aad: ByteArray): ByteArray {
        enter("seal")
        return delegate.seal(id, plaintext, aad)
    }

    override fun open(id: WalletId, combined: ByteArray, aad: ByteArray): ByteArray {
        enter("open")
        return delegate.open(id, combined, aad)
    }

    override fun delete(id: WalletId) {
        enter("delete")
        delegate.delete(id)
    }

    override fun isKeyLost(id: WalletId): Boolean {
        enter("isKeyLost")
        return delegate.isKeyLost(id)
    }

    override fun publicDataKey(createIfMissing: Boolean): PublicDataKey? {
        enter("publicDataKey")
        return delegate.publicDataKey(createIfMissing)
    }
}
