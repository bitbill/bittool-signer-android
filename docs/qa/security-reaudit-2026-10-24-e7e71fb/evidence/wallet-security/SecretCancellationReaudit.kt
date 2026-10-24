import com.bittool.signer.core.crypto.Bip39Seed
import com.bittool.signer.core.crypto.Pbkdf2Bip39Seed
import com.bittool.signer.core.data.DefaultSignerStore
import com.bittool.signer.core.data.FakeEngine
import com.bittool.signer.core.data.JvmStore
import com.bittool.signer.core.model.SecretText
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent

private fun replace(store: DefaultSignerStore, name: String, value: Any) {
    DefaultSignerStore::class.java.getDeclaredField(name).apply { isAccessible = true }.set(store, value)
}

/** Test only: inspect the actual coroutine lambda's holder, never print its contents. */
private fun canonicalHolder(task: Any): Any? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    fun search(value: Any?, depth: Int): Any? {
        if (value == null || depth > 10 || !seen.add(value)) return null
        val name = value.javaClass.name
        if (name.contains("DefaultSignerStore\$canonical\$")) {
            val field = value.javaClass.declaredFields.firstOrNull { it.name == "\$words" }
            if (field != null) return field.apply { isAccessible = true }.get(value)
        }
        if (!(name.startsWith("kotlin.") || name.startsWith("kotlinx.coroutines.") || name.startsWith("com.bittool."))) return null
        var klass: Class<*>? = value.javaClass
        while (klass != null && klass != Any::class.java) {
            for (field in klass.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                val child = runCatching { field.isAccessible = true; field.get(value) }.getOrNull()
                search(child, depth + 1)?.let { return it }
            }
            klass = klass.superclass
        }
        return null
    }
    return search(task, 0)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun indexSeedCancellation() = JvmStore.run(separateDefault = true) {
    store.reload()
    val producer = Pbkdf2Bip39Seed()
    var retained: CharArray? = null
    lateinit var operation: Job
    replace(store, "bip39Seed", object : Bip39Seed {
        override fun hex(mnemonic: SecretText, passphrase: SecretText): CharArray =
            producer.hex(mnemonic, passphrase).also { retained = it; operation.cancel() }
    })
    val job = test.async {
        operation = currentCoroutineContext()[Job]!!
        SecretText.of(FakeEngine.PHRASE).use { phrase ->
            SecretText.empty().use { passphrase -> store.existingWallet(phrase, passphrase) }
        }
    }
    test.runCurrent()
    val seed = checkNotNull(retained)
    check(job.isCancelled && seed.size == 128 && seed.all { it == '\u0000' })
    check(engine.count("derivePublicIndex") == 0)
    println("PASS publicIndex: cancelled=true seedProduced=true seedLength=128 seedWiped=true engineCalls=0")
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun signingSeedCancellation() = JvmStore.run(separateDefault = true) {
    store.reload()
    create()
    store.prepare(FakeEngine.request())
    val task = state.pendingTasks.single()
    val producer = Pbkdf2Bip39Seed()
    var retained: CharArray? = null
    lateinit var operation: Job
    replace(store, "bip39Seed", object : Bip39Seed {
        override fun hex(mnemonic: SecretText, passphrase: SecretText): CharArray =
            producer.hex(mnemonic, passphrase).also { retained = it; operation.cancel() }
    })
    val job = test.async {
        operation = currentCoroutineContext()[Job]!!
        sign(task.id)
    }
    test.runCurrent()
    val seed = checkNotNull(retained)
    check(job.isCancelled && seed.size == 128 && seed.all { it == '\u0000' })
    check(engine.count("signPlanTask") == 0)
    check(vault.issuedSecrets.last().mnemonic.isEmpty())
    check(!state.busy && state.completedTaskIds.isEmpty())
    println("PASS signing: cancelled=true seedProduced=true seedLength=128 seedWiped=true engineCalls=0 mnemonicClosed=true busy=false completedTasks=0")
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun canonicalCancellation() = JvmStore.run(separateDefault = true) {
    store.reload()
    var retained: SecretText? = null
    var retainedChars: CharArray? = null
    lateinit var operation: Job
    replace(store, "default", object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatcher.dispatch(context, Runnable {
                val holder = canonicalHolder(block)
                block.run()
                if (holder != null) {
                    val value = holder.javaClass.getDeclaredField("element").get(holder)
                    if (value is SecretText && value.isNotEmpty()) {
                        check(retained == null)
                        retained = value
                        retainedChars = value.chars()
                        // The canonical words already exist. Main-thread resumption is queued, so cancellation
                        // occurs at precisely the withContext return boundary from the original report.
                        operation.cancel()
                    }
                }
            })
        }
    })
    val job = test.async {
        operation = currentCoroutineContext()[Job]!!
        SecretText.of(FakeEngine.PHRASE).use { phrase ->
            SecretText.empty().use { passphrase -> store.existingWallet(phrase, passphrase) }
        }
    }
    test.runCurrent()
    val words = checkNotNull(retained) { "Canonical coroutine holder was not captured" }
    val chars = checkNotNull(retainedChars)
    check(job.isCancelled && chars.isNotEmpty() && chars.all { it == '\u0000' } && words.isEmpty())
    check(engine.count("derivePublicIndex") == 0)
    println("PASS canonical: cancelled=true wordsProduced=true originalCharsWiped=true secretClosed=true engineCalls=0")
}

fun main() {
    // JVM-only harness: the production fsync uses Android Os, unavailable on the host. This changes no files or
    // production source and is the same test seam used by the repository's AtomicFiles tests.
    val atomic = com.bittool.signer.core.common.storage.AtomicFiles
    atomic.javaClass.getDeclaredField("directorySync").apply { isAccessible = true }
        .set(atomic, { _: java.io.File -> Unit })
    indexSeedCancellation()
    signingSeedCancellation()
    canonicalCancellation()
    println("RESULT: 3/3 deterministic cancellation paths pass")
}
