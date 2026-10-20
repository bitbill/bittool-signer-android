package com.bittool.signer.core.data

import com.bittool.signer.core.data.doubles.StoreFixture
import com.bittool.signer.core.data.doubles.asSecret
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletPublicMetadata
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * One store over a temporary directory with the [FakeEngine] and the Wave-1 doubles, all on the test scheduler (main,
 * default and I/O are one [StandardTestDispatcher], so virtual time drives the 600 ms prefetch delay).
 */
class JvmStore(val test: TestScope) {
    val engine = FakeEngine()
    val dir: File = Files.createTempDirectory("signer-store").toFile()
    val dispatcher = StandardTestDispatcher(test.testScheduler)
    /** The store's application scope: foreground work for the scheduler (unlike `backgroundScope`), cancelled at the end. */
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val fixture = StoreFixture(
        dataDir = dir,
        engine = engine,
        main = dispatcher,
        io = dispatcher,
        default = dispatcher,
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

        fun run(body: suspend JvmStore.() -> Unit) = runTest {
            val store = JvmStore(this)
            try {
                store.body()
            } finally {
                store.scope.cancel()
                store.dir.deleteRecursively()
            }
        }
    }
}
