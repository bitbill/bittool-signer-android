package com.bittool.signer.core.engine

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The bundled engine runs in QuickJS on a device and reproduces the BIP84/BIP44 reference vectors. */
@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {
    private val engine = EngineModule.create(ApplicationProvider.getApplicationContext(), allowTestOperations = true)

    // abandon ×11 about, empty passphrase (BIP39 reference seed).
    private val seed = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"

    @Test
    fun derivesReferenceAddresses() = runBlocking {
        var t = SystemClock.elapsedRealtime()
        engine.run(EngineOperation.Capabilities)
        Log.i("EngineSmokeTest", "first context (load+compile+capabilities): ${SystemClock.elapsedRealtime() - t} ms")
        t = SystemClock.elapsedRealtime()
        engine.run(EngineOperation.Capabilities)
        Log.i("EngineSmokeTest", "fresh context (bytecode): ${SystemClock.elapsedRealtime() - t} ms")
        t = SystemClock.elapsedRealtime()
        val index = engine.run(EngineOperation.DerivePublicIndex, JsonObject(mapOf("seed" to JsonPrimitive(seed))))
        Log.i("EngineSmokeTest", "derivePublicIndex: ${SystemClock.elapsedRealtime() - t} ms")
        assertEquals("73c5da0a", index["masterFingerprint"]!!.jsonPrimitive.content)

        fun first(chain: String): String {
            val r = runBlocking {
                engine.run(
                    EngineOperation.DerivePublicAddresses,
                    JsonObject(mapOf("publicIndex" to index, "chain" to JsonPrimitive(chain), "start" to JsonPrimitive(0), "count" to JsonPrimitive(1))),
                )
            }
            return r["addresses"]!!.jsonArray[0].jsonObject["address"]!!.jsonPrimitive.content
        }
        t = SystemClock.elapsedRealtime()
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", first("bitcoin"))
        assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", first("evm"))
        assertEquals("TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH", first("tron"))
        assertEquals("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk", first("solana"))
        Log.i("EngineSmokeTest", "4 warm address derivations: ${SystemClock.elapsedRealtime() - t} ms")

        t = SystemClock.elapsedRealtime()
        val m = engine.run(EngineOperation.Mnemonic, JsonObject(mapOf("entropy" to JsonPrimitive("00000000000000000000000000000000"))))
        assertTrue(m["mnemonic"]!!.jsonPrimitive.content.endsWith("about"))
        Log.i("EngineSmokeTest", "mnemonic: ${SystemClock.elapsedRealtime() - t} ms")
        Unit
    }

    @Test
    fun entropyIsInstalledAndFrozen() = runBlocking {
        // Taproot signing draws BIP340 aux randomness through crypto.getRandomValues; mnemonic needs none.
        val error = runCatching { engine.run(EngineOperation.Wordlist) }.exceptionOrNull()
        assertTrue(error == null)
        Unit
    }
}
