package com.bittool.signer.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The instrumented tests run on the production `:core:crypto` and `:core:scanner` implementations (RealStoreFixture,
 * DeviceStore), so the JVM tests' crypto and encoder doubles must not be compiled into this APK, where a test could pick
 * one up by accident and silently stop exercising the real crypto. Only the shared engine decorator and public fixtures
 * of `src/testShared` belong here.
 */
@RunWith(AndroidJUnit4::class)
class TestApkContentsTest {
    @Test
    fun theJvmCryptoDoublesAreNotPackaged() {
        for (name in JVM_ONLY) {
            assertFailsWith<ClassNotFoundException>("$name is a JVM-only double") { Class.forName("$DOUBLES.$name") }
        }
        assertNull(javaClass.getResource("/bip39/bip39-english.txt"), "the English-only BIP39 double's word list")
        for (name in SHARED) assertNotNull(Class.forName("$DOUBLES.$name"))
    }

    private companion object {
        const val DOUBLES = "com.bittool.signer.core.data.doubles"
        val JVM_ONLY = listOf(
            "StoreFixture",
            "InMemoryWalletVault",
            "JvmSecureRandomSource",
            "HmacBip39Seed",
            "EnglishBip39",
            "HmacPublicDataKey",
            "AesGcmJournalCipher",
            "FakeQrCodeEncoder",
        )
        val SHARED = listOf("ControlledSigningEngine", "EngineGate", "PublicFixtures", "SecretsKt")
    }
}
