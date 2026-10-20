package com.bittool.signer.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.crypto.SecureRandomSource
import com.bittool.signer.core.data.doubles.ControlledSigningEngine
import com.bittool.signer.core.data.doubles.JvmSecureRandomSource
import com.bittool.signer.core.data.doubles.PublicFixtures
import com.bittool.signer.core.data.doubles.StoreFixture
import com.bittool.signer.core.data.doubles.asSecret
import com.bittool.signer.core.engine.EngineModule
import com.bittool.signer.core.engine.SignerEngine
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** The bundled engine, loaded once per test process (verify + compile ≈ 0.3 s), with the debug test operations. */
object DeviceEngine {
    val context: Context get() = ApplicationProvider.getApplicationContext()
    val real: SignerEngine by lazy { EngineModule.create(context, allowTestOperations = true) }
}

/**
 * One `DefaultSignerStore` over a fresh directory, the real QuickJS engine behind the iOS-style
 * [ControlledSigningEngine], and the Wave-1 crypto doubles — with the production dispatchers: state on the main
 * thread, vault and BIP39 on `Default`, files on `IO`, the engine on its own thread. The test body runs **on the main
 * thread** (iOS tests are `@MainActor`), so it may call the store's non-suspending functions directly, and it is a
 * [CoroutineScope] on that thread for `async` calls held behind gates.
 */
class DeviceStore private constructor(
    val engine: ControlledSigningEngine,
    val fixture: StoreFixture,
    mainScope: CoroutineScope,
) : CoroutineScope by mainScope {
    val store: DefaultSignerStore get() = fixture.store
    val state: SignerState get() = store.state.value
    val vault get() = fixture.vault

    /** iOS `makeStore` (SessionTests.swift:456-464): an encrypted wallet, loaded, and repaired unless [complete] is false. */
    suspend fun makeStore(complete: Boolean = true): VaultMetadata {
        val wallet = fixture.createVaultOnly("Public session fixture", PublicFixtures.PHRASE, PASSWORD)
        store.reload()
        if (complete) PASSWORD.asSecret { store.completeSetup(wallet.id, it) }
        return wallet
    }

    suspend fun createWallet(name: String, passphrase: String = "", password: String = PASSWORD, phrase: String = PublicFixtures.PHRASE): VaultMetadata =
        phrase.asSecret { words -> passphrase.asSecret { pass -> password.asSecret { pw -> store.createWallet(name, words, pass, pw) } } }

    suspend fun sign(taskId: String, password: String = PASSWORD, risk: Boolean = false) = password.asSecret { store.sign(taskId, it, risk) }

    fun sidecar(id: WalletId): File = fixture.publicRepository.file(id)

    /** Polls [condition] on the main thread every 50 ms, up to [attempts] times (iOS `Task.sleep` loops). */
    suspend fun eventually(attempts: Int = 100, condition: () -> Boolean): Boolean {
        repeat(attempts) {
            if (condition()) return true
            delay(50)
        }
        return condition()
    }

    companion object {
        const val PASSWORD = "Session-Password1"

        fun run(random: SecureRandomSource = JvmSecureRandomSource(), body: suspend DeviceStore.() -> Unit) = runBlocking {
            val dir = File(DeviceEngine.context.cacheDir, "signer-store-" + UUID.randomUUID()).apply { mkdirs() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val engine = ControlledSigningEngine(DeviceEngine.real)
            val fixture = StoreFixture(
                dataDir = dir,
                engine = engine,
                main = Dispatchers.Main.immediate,
                io = Dispatchers.IO,
                default = Dispatchers.Default,
                scope = scope,
                random = random,
            )
            try {
                withTimeout(240_000) { withContext(Dispatchers.Main) { DeviceStore(engine, fixture, this).body() } }
            } finally {
                scope.cancel()
                DeviceEngine.real.endScanSession()
                DeviceEngine.real.releaseWarmContext()
                dir.deleteRecursively()
            }
        }
    }
}
