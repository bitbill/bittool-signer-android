package com.bittool.signer.core.crypto

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.crypto.kdf.NativePbkdf2
import com.bittool.signer.core.crypto.kdf.Pbkdf2HmacSha256
import com.bittool.signer.core.model.util.Hex
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PBKDF2-HMAC-SHA256 candidates at the vault's 600,000 iterations on the device (vault-crypto.md §17.6). Each is
 * first checked for correctness, then timed (median of [RUNS] after a warm-up). The two JCA candidates are timed at
 * 1/10 of the iterations and scaled ×10, because at full length the pre-keyed `Mac` loop allocates so much that the
 * low-memory killer ended the test process (1 GB RSS) on the shared emulator. Results are logged under
 * `Pbkdf2Benchmark` and recorded in the W1-crypto report; the production choice must stay the fastest.
 */
@RunWith(AndroidJUnit4::class)
class Pbkdf2BenchmarkTest {
    private val password = "Test-Password1".encodeToByteArray()
    private val salt = ByteArray(32) { it.toByte() }
    private val vector = "05b079f15d09a3589c02ca352a3dd38ad23daf4663a59d6072c2333ddb4901d9"

    @Test
    fun nativeAcceleratedIsTheFastestCorrectCandidate() {
        assertTrue("the native library loads on the device", NativePbkdf2.isAvailable)
        log("native accelerated=${NativePbkdf2.accelerated()}; SecretKeyFactory=${SecretKeyFactory.getInstance(JCA).provider.name}; Mac=${Mac.getInstance(MAC).provider.name}")

        // Correctness of every candidate: the spec vector at full length for the production paths, and agreement
        // with it at 1,000 iterations for the JCA ones.
        assertEquals(vector, Hex.encode(native(ITERATIONS)))
        assertEquals(vector, Hex.encode(nativePortable(ITERATIONS)))
        assertEquals(vector, Hex.encode(kotlin(ITERATIONS)))
        val short = Hex.encode(kotlin(1_000))
        assertEquals(short, Hex.encode(macLoop(1_000)))
        assertEquals(short, Hex.encode(secretKeyFactory(1_000)))

        val nativeMs = median("native (ARMv8 SHA-2 when present)") { native(ITERATIONS) }
        val portableMs = median("native portable C") { nativePortable(ITERATIONS) }
        val kotlinMs = median("word-level Kotlin") { kotlin(ITERATIONS) }
        val macMs = median("pre-keyed Mac(HmacSHA256) loop ×10", scale = 10) { macLoop(ITERATIONS / 10) }
        val jcaMs = median("SecretKeyFactory(PBKDF2WithHmacSHA256) ×10", scale = 10) { secretKeyFactory(ITERATIONS / 10) }
        log("summary at 600,000 iterations, median ms: native=$nativeMs portableC=$portableMs kotlin=$kotlinMs mac=$macMs secretKeyFactory=$jcaMs")

        assertTrue("native must beat every JVM candidate", nativeMs <= minOf(kotlinMs, macMs, jcaMs))
        assertTrue("600,000 iterations within 1 s on the emulator ($nativeMs ms)", nativeMs <= 1_000)
    }

    private fun native(iterations: Int): ByteArray =
        ByteArray(32).also { assertEquals(0, NativePbkdf2.pbkdf2HmacSha256(password, salt, iterations, it)) }

    private fun nativePortable(iterations: Int): ByteArray =
        ByteArray(32).also { assertEquals(0, NativePbkdf2.pbkdf2HmacSha256Portable(password, salt, iterations, it)) }

    private fun kotlin(iterations: Int): ByteArray = Pbkdf2HmacSha256.derive(password, salt, iterations, 32)

    private fun secretKeyFactory(iterations: Int): ByteArray =
        SecretKeyFactory.getInstance(JCA).generateSecret(PBEKeySpec("Test-Password1".toCharArray(), salt, iterations, 256)).encoded

    private fun macLoop(iterations: Int): ByteArray {
        val mac = Mac.getInstance(MAC)
        mac.init(SecretKeySpec(password, MAC))
        val u = ByteArray(32)
        val t = ByteArray(32)
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        mac.doFinal(u, 0)
        u.copyInto(t)
        for (j in 2..iterations) {
            mac.update(u)
            mac.doFinal(u, 0)
            for (k in 0 until 32) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
        }
        return t
    }

    private fun median(name: String, scale: Int = 1, derive: () -> ByteArray): Long {
        derive() // warm-up (JIT)
        val times = LongArray(RUNS) {
            val start = SystemClock.elapsedRealtimeNanos()
            derive()
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000 * scale
        }.sorted()
        log("$name: ${times.joinToString()} ms")
        return times[RUNS / 2]
    }

    private fun log(message: String) = Log.i("Pbkdf2Benchmark", message)

    private companion object {
        const val ITERATIONS = 600_000
        const val RUNS = 5
        const val JCA = "PBKDF2WithHmacSHA256"
        const val MAC = "HmacSHA256"
    }
}
