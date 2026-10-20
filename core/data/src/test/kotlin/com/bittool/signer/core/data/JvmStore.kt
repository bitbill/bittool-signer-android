package com.bittool.signer.core.data

import com.bittool.signer.core.data.doubles.StoreFixture
import com.bittool.signer.core.data.doubles.asSecret
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletPublicMetadata
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * One store over a temporary directory with the [FakeEngine] and the Wave-1 doubles, all on the test scheduler (main,
 * default and I/O are one [StandardTestDispatcher], so virtual time drives the 600 ms prefetch delay).
 *
 * With [separateDefault] the store's `Default` work (vault, BIP39 seed, freezing) runs on [default], a
 * [HoldableDispatcher] over the same scheduler: switching to it is then a real suspension point, and a test can stop the
 * store inside one (iOS does that work synchronously on the main actor, so these are Android-only race windows).
 */
class JvmStore(val test: TestScope, separateDefault: Boolean = false) {
    val engine = FakeEngine()
    val dir: File = Files.createTempDirectory("signer-store").toFile()
    val dispatcher = StandardTestDispatcher(test.testScheduler)
    val default = HoldableDispatcher(dispatcher)
    /** The store's application scope: foreground work for the scheduler (unlike `backgroundScope`), cancelled at the end. */
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val fixture = StoreFixture(
        dataDir = dir,
        engine = engine,
        main = dispatcher,
        io = dispatcher,
        default = if (separateDefault) default else dispatcher,
        scope = scope,
    )
    val store: DefaultSignerStore get() = fixture.store
    val vault get() = fixture.vault
    val state: SignerState get() = store.state.value

    /** A wallet created through the store (index, sidecar, vault), like a finished setup. */
    suspend fun create(name: String = "Public session fixture", passphrase: String = "", password: String = PASSWORD): VaultMetadata =
        FakeEngine.PHRASE.asSecret { words ->
            passphrase.asSecret { pass -> password.asSecret { pw -> store.createWallet(name, words, pass, pw) } }
        }

    /** The synthetic index the fake engine derives for [passphrase]. */
    fun indexFor(passphrase: String = ""): WalletPublicMetadata {
        val seed = FakeEngine.PHRASE.asSecret { words -> passphrase.asSecret { fixture.seed.hex(words, it) } }
        return WalletPublicMetadata.parse(FakeEngine.publicIndex(String(seed)))
    }

    suspend fun sign(taskId: String, password: String = PASSWORD, risk: Boolean = false) =
        password.asSecret { store.sign(taskId, it, risk) }

    fun sidecar(id: WalletId): File = fixture.publicRepository.file(id)

    companion object {
        const val PASSWORD = "Session-Password1"

        fun run(separateDefault: Boolean = false, body: suspend JvmStore.() -> Unit) = runTest {
            val store = JvmStore(this, separateDefault)
            try {
                store.body()
            } finally {
                store.default.release()
                store.scope.cancel()
                store.dir.deleteRecursively()
            }
        }
    }
}

/**
 * Runs everything on [delegate], except while held: from [holdAfter] on, after letting the given number of dispatches
 * through, each dispatched block waits until [release]. Lets a test freeze a coroutine exactly at a dispatcher switch.
 */
class HoldableDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    private val queued = ArrayList<Pair<CoroutineContext, Runnable>>()
    private var passes = 0
    private var holding = false

    /** How many dispatched blocks are waiting. */
    val waiting: Int get() = synchronized(queued) { queued.size }

    /** Lets the next [passes] dispatches run, then holds every further one until [release]. */
    fun holdAfter(passes: Int = 0) = synchronized(queued) {
        this.passes = passes
        holding = true
    }

    /** Stops holding and runs the waiting blocks in order. */
    fun release() {
        val pending = synchronized(queued) {
            holding = false
            queued.toList().also { queued.clear() }
        }
        pending.forEach { (context, block) -> delegate.dispatch(context, block) }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val held = synchronized(queued) {
            when {
                !holding -> false
                passes > 0 -> {
                    passes--
                    false
                }
                else -> {
                    queued += context to block
                    true
                }
            }
        }
        if (!held) delegate.dispatch(context, block)
    }
}
