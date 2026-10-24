import com.bittool.signer.core.crypto.Bip39Seed
import com.bittool.signer.core.data.DefaultSignerStore
import com.bittool.signer.core.data.FakeEngine
import com.bittool.signer.core.data.JvmStore
import com.bittool.signer.core.model.SecretText
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent

fun main() = JvmStore.run(separateDefault = true) {
    store.reload()
    val delegate = com.bittool.signer.core.crypto.Pbkdf2Bip39Seed()
    var retained: CharArray? = null
    lateinit var operation: Job
    val spy = object : Bip39Seed {
        override fun hex(mnemonic: SecretText, passphrase: SecretText): CharArray {
            val value = delegate.hex(mnemonic, passphrase)
            retained = value
            operation.cancel()
            return value
        }
    }
    DefaultSignerStore::class.java.getDeclaredField("bip39Seed").apply { isAccessible = true }.set(store, spy)
    val job = test.async {
        operation = currentCoroutineContext()[Job]!!
        SecretText.of(FakeEngine.PHRASE).use { phrase ->
            SecretText.empty().use { passphrase -> store.existingWallet(phrase, passphrase) }
        }
    }
    test.runCurrent()
    println("operationCancelled=" + job.isCancelled)
    println("seedProduced=" + (retained != null))
    println("seedLength=" + retained!!.size)
    println("seedWiped=" + retained!!.all { it == '\u0000' })
    println("derivePublicIndexCalls=" + engine.count("derivePublicIndex"))
    check(job.isCancelled && retained != null && retained!!.any { it != '\u0000' })
    retained!!.fill('\u0000')
}
