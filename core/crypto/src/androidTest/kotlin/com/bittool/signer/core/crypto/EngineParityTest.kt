package com.bittool.signer.core.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.crypto.bip39.Bip39WordlistSource
import com.bittool.signer.core.crypto.bip39.DefaultBip39
import com.bittool.signer.core.crypto.kdf.NativePbkdf2
import com.bittool.signer.core.crypto.kdf.Pbkdf2HmacSha256
import com.bittool.signer.core.engine.EngineModule
import com.bittool.signer.core.engine.EngineOperation
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.util.Hex
import java.util.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The instrumented halves of iOS `HardeningTests` (`BitToolSignerTests/HardeningTests.swift:27-47`): the native seed
 * derives the same wallet as the engine, and the bundled English list equals the engine's (`wordlist` test operation).
 * Also: all ten lists load from the APK assets with their pinned hashes, and the native PBKDF2 agrees with Kotlin.
 */
@RunWith(AndroidJUnit4::class)
class EngineParityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = EngineModule.create(context, allowTestOperations = true)
    private val bip39 = DefaultBip39(Bip39WordlistSource.assets(context.assets))
    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun nativeSeedDerivesTheSameWalletAsTheEngine() = runBlocking {
        for (passphrase in listOf("", "Public passphrase ✓")) {
            val fromPhrase = engine.run(
                EngineOperation.DerivePublicIndex,
                JsonObject(mapOf("mnemonic" to JsonPrimitive(phrase), "passphrase" to JsonPrimitive(passphrase))),
            )
            val seed = Pbkdf2Bip39Seed().hex(SecretText.of(phrase), SecretText.of(passphrase))
            val fromSeed = engine.run(EngineOperation.DerivePublicIndex, JsonObject(mapOf("seed" to JsonPrimitive(String(seed)))))
            seed.fill('\u0000')
            assertEquals(WalletPublicMetadata.parse(fromPhrase).canonicalJson, WalletPublicMetadata.parse(fromSeed).canonicalJson)
        }
    }

    @Test
    fun bundledWordlistIsCanonicalAndMatchesTheEngine() = runBlocking {
        val list = bip39.wordlist(Bip39Language.English)
        assertTrue(list.isAvailable)
        val words = engine.run(EngineOperation.Wordlist).getValue("words").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(list.words, words)
        assertEquals(listOf("abandon", "ability", "able"), list.suggestions("ab", limit = 3))
    }

    @Test
    fun allTenListsLoadFromTheApkAssets() {
        for (language in Bip39Language.entries) assertTrue(language.fileName, bip39.wordlist(language).isAvailable)
        assertEquals(Bip39Language.Japanese, bip39.canonicalize(
            "あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あいこくしん　あおぞら",
        ).language)
    }

    @Test
    fun nativeAndKotlinPbkdf2Agree() {
        assertTrue(NativePbkdf2.isAvailable)
        val random = Random(3)
        for (round in 0 until 40) {
            val password = ByteArray(random.nextInt(130) + 1).also(random::nextBytes)
            val salt = ByteArray(random.nextInt(80)).also(random::nextBytes)
            val iterations = 1 + random.nextInt(2_000)
            val length = 1 + random.nextInt(96)
            val kotlin = Pbkdf2HmacSha256.derive(password, salt, iterations, length)
            val native = ByteArray(length).also { assertEquals(0, NativePbkdf2.pbkdf2HmacSha256(password, salt, iterations, it)) }
            val portable = ByteArray(length).also { assertEquals(0, NativePbkdf2.pbkdf2HmacSha256Portable(password, salt, iterations, it)) }
            assertEquals("round $round", Hex.encode(kotlin), Hex.encode(native))
            assertEquals("round $round", Hex.encode(kotlin), Hex.encode(portable))
        }
        // Refused arguments leave the output untouched.
        val out = ByteArray(32)
        assertTrue(NativePbkdf2.pbkdf2HmacSha256(ByteArray(1), ByteArray(1), 0, out) < 0)
        assertTrue(NativePbkdf2.pbkdf2HmacSha256(ByteArray(4_097), ByteArray(1), 1, out) < 0)
        assertTrue(out.all { it == 0.toByte() })
    }
}
