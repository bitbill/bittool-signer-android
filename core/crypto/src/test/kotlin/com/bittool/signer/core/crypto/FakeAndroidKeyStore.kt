package com.bittool.signer.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.Key
import java.security.KeyStoreException
import java.security.Provider.Service
import java.security.KeyStoreSpi
import java.security.ProviderException
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.KeyGeneratorSpi
import javax.crypto.Mac
import javax.crypto.MacSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A JVM stand-in for the platform's `AndroidKeyStore` JCA provider (tests only, under Robolectric for
 * [KeyGenParameterSpec]), so the production Keystore code (`AndroidKeystoreDeviceKeyStore`) runs against the failure
 * modes a device cannot be made to show on demand: a Keystore that does not answer, a live key that reads as missing
 * (the API 26–30 binder pitfall), a permanently invalidated entry, a key that only fails to load, a locked device.
 *
 * Like the platform: keys are non-exportable handles (`encoded == null`) usable only through this provider's `Cipher`
 * (`AES/GCM/NoPadding`, random 12-byte IV) and `Mac` (`HmacSHA256`); `KeyGenerator.generateKey()` silently replaces an
 * existing alias; reads that fail report "missing" (`containsAlias` false, `getKey` null) instead of throwing.
 *
 * [install] puts it first in the provider list (keeping a provider of the same name aside); [uninstall] restores it.
 */
class FakeAndroidKeyStore : java.security.Provider("AndroidKeyStore", 1.0, "JVM stand-in for the Android Keystore") {
    private class KeyEntry(val algorithm: String, val bytes: ByteArray)

    private val keyEntries = LinkedHashMap<String, KeyEntry>()
    private val random = SecureRandom()

    /** Keystore does not answer at all: every read reports nothing, every write and generation fails. */
    @Volatile var outage = false

    /** Evaluated on every read of a live alias (containsAlias, getKey, each alias aliases() lists): true reads it as missing. */
    @Volatile var readsMissing: (alias: String) -> Boolean = { false }

    /** Called after a key is generated under the alias. */
    @Volatile var onGenerated: (alias: String) -> Unit = {}

    /** Entries Keystore refuses with [KeyPermanentlyInvalidatedException] when a `Cipher` or `Mac` is initialised. */
    val invalidated: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Entries whose `getKey` throws the given exception (e.g. keystore2's `UnrecoverableKeyException`). */
    val loadFailures: MutableMap<String, Exception> = Collections.synchronizedMap(HashMap())

    /** The device is locked: using any key throws [UserNotAuthenticatedException] (`setUnlockedDeviceRequired`). */
    @Volatile var locked = false

    /** Every alias a key was generated under, in order (replacements included). */
    val generated: MutableList<String> = Collections.synchronizedList(ArrayList())

    private var previous: java.security.Provider? = null

    init {
        putService(object : Service(this, "KeyStore", "AndroidKeyStore", FakeKeyStoreSpi::class.java.name, null, null) {
            override fun newInstance(constructorParameter: Any?): Any = FakeKeyStoreSpi()
        })
        for (algorithm in listOf(AES, HMAC)) {
            putService(object : Service(this, "KeyGenerator", algorithm, FakeKeyGeneratorSpi::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = FakeKeyGeneratorSpi(algorithm)
            })
        }
        putService(object : Service(this, "Cipher", TRANSFORMATION, FakeCipherSpi::class.java.name, null, null) {
            override fun supportsParameter(parameter: Any?): Boolean = parameter is FakeKey
            override fun newInstance(constructorParameter: Any?): Any = FakeCipherSpi()
        })
        putService(object : Service(this, "Mac", HMAC, FakeMacSpi::class.java.name, null, null) {
            override fun supportsParameter(parameter: Any?): Boolean = parameter is FakeKey
            override fun newInstance(constructorParameter: Any?): Any = FakeMacSpi()
        })
    }

    fun install() {
        previous = Security.getProvider(name)
        if (previous != null) Security.removeProvider(name)
        Security.insertProviderAt(this, 1)
    }

    fun uninstall() {
        Security.removeProvider(name)
        previous?.let { Security.insertProviderAt(it, 1) }
    }

    /** Whether an entry really exists under [alias] (whatever reads report). */
    fun holds(alias: String): Boolean = synchronized(keyEntries) { alias in keyEntries }

    /** The aliases that really exist. */
    fun aliases(): List<String> = synchronized(keyEntries) { keyEntries.keys.toList() }

    /** Removes [alias] for real, as if Keystore had lost it. */
    fun lose(alias: String) {
        synchronized(keyEntries) { keyEntries.remove(alias) }
    }

    private fun visible(alias: String): Boolean = !outage && holds(alias) && !readsMissing(alias)

    private fun entry(alias: String): KeyEntry? = synchronized(keyEntries) { keyEntries[alias] }

    /** A non-exportable key handle, usable only through this provider (like `AndroidKeyStoreSecretKey`). */
    inner class FakeKey(val alias: String, private val algorithm: String) : SecretKey {
        override fun getAlgorithm(): String = algorithm
        override fun getFormat(): String? = null
        override fun getEncoded(): ByteArray? = null

        /** The key material for this provider's Cipher and Mac, after the checks Keystore makes on every use. */
        fun material(): SecretKeySpec {
            if (outage) throw ProviderException("Keystore operation failed")
            if (alias in invalidated) throw KeyPermanentlyInvalidatedException()
            if (locked) throw UserNotAuthenticatedException()
            val entry = entry(alias) ?: throw InvalidKeyException("Keystore key not found")
            return SecretKeySpec(entry.bytes, entry.algorithm)
        }
    }

    inner class FakeKeyStoreSpi : KeyStoreSpi() {
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit

        override fun engineContainsAlias(alias: String): Boolean = visible(alias)

        override fun engineGetKey(alias: String, password: CharArray?): Key? {
            if (outage) return null
            loadFailures[alias]?.let { throw it }
            if (!visible(alias)) return null
            val entry = entry(alias) ?: return null
            return FakeKey(alias, entry.algorithm)
        }

        override fun engineAliases(): Enumeration<String> =
            Collections.enumeration(if (outage) emptyList() else aliases().filter { !readsMissing(it) })

        override fun engineDeleteEntry(alias: String) {
            if (outage) throw KeyStoreException("Keystore does not answer")
            lose(alias)
        }

        override fun engineSize(): Int = aliases().size
        override fun engineIsKeyEntry(alias: String): Boolean = visible(alias)
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineGetCreationDate(alias: String): Date? = if (visible(alias)) Date(0) else null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) =
            throw KeyStoreException("not supported by the test Keystore")
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) =
            throw KeyStoreException("not supported by the test Keystore")
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw KeyStoreException("not supported")
        override fun engineStore(stream: OutputStream?, password: CharArray?) = throw UnsupportedOperationException()
    }

    inner class FakeKeyGeneratorSpi(private val algorithm: String) : KeyGeneratorSpi() {
        private var spec: KeyGenParameterSpec? = null

        override fun engineInit(random: SecureRandom?) = throw IllegalStateException("a KeyGenParameterSpec is required")
        override fun engineInit(keysize: Int, random: SecureRandom?) = throw IllegalStateException("a KeyGenParameterSpec is required")
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            spec = params as? KeyGenParameterSpec ?: throw InvalidAlgorithmParameterException("KeyGenParameterSpec required")
        }

        override fun engineGenerateKey(): SecretKey {
            val spec = checkNotNull(spec)
            if (outage) throw ProviderException("Keystore key generation failed")
            val bits = spec.keySize.takeIf { it > 0 } ?: 256
            val bytes = ByteArray(bits / 8).also(random::nextBytes)
            // The platform pitfall (vault-crypto.md §17.3): an existing alias is silently replaced.
            synchronized(keyEntries) { keyEntries[spec.keystoreAlias] = KeyEntry(algorithm, bytes) }
            generated += spec.keystoreAlias
            onGenerated(spec.keystoreAlias)
            return FakeKey(spec.keystoreAlias, algorithm)
        }
    }

    inner class FakeCipherSpi : CipherSpi() {
        private var inner: Cipher? = null

        private fun cipher(): Cipher = checkNotNull(inner) { "not initialised" }

        private fun start(opmode: Int, key: Key?, init: Cipher.(SecretKeySpec) -> Unit) {
            val material = (key as? FakeKey ?: throw InvalidKeyException("not a test Keystore key")).material()
            inner = Cipher.getInstance(TRANSFORMATION, "SunJCE").apply { init(material) }
        }

        override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) = start(opmode, key) { init(opmode, it) }
        override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) =
            start(opmode, key) { init(opmode, it, params) }
        override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameters?, random: SecureRandom?) =
            start(opmode, key) { init(opmode, it, params) }

        override fun engineSetMode(mode: String?) = Unit
        override fun engineSetPadding(padding: String?) = Unit
        override fun engineGetBlockSize(): Int = 16
        override fun engineGetOutputSize(inputLen: Int): Int = cipher().getOutputSize(inputLen)
        override fun engineGetIV(): ByteArray? = inner?.iv
        override fun engineGetParameters(): AlgorithmParameters? = inner?.parameters
        override fun engineUpdateAAD(src: ByteArray, offset: Int, len: Int) = cipher().updateAAD(src, offset, len)
        override fun engineUpdateAAD(src: ByteBuffer) = cipher().updateAAD(src)
        override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray? =
            cipher().update(input, inputOffset, inputLen)
        override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int, output: ByteArray, outputOffset: Int): Int =
            cipher().update(input, inputOffset, inputLen, output, outputOffset)
        override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
            if (input == null) cipher().doFinal() else cipher().doFinal(input, inputOffset, inputLen)
        override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray, outputOffset: Int): Int =
            cipher().doFinal(input ?: ByteArray(0), inputOffset, inputLen, output, outputOffset)
    }

    inner class FakeMacSpi : MacSpi() {
        private var inner: Mac? = null

        private fun mac(): Mac = checkNotNull(inner) { "not initialised" }

        override fun engineInit(key: Key?, params: AlgorithmParameterSpec?) {
            val material = (key as? FakeKey ?: throw InvalidKeyException("not a test Keystore key")).material()
            inner = Mac.getInstance(HMAC, "SunJCE").apply { init(material) }
        }

        override fun engineGetMacLength(): Int = 32
        override fun engineUpdate(input: Byte) = mac().update(input)
        override fun engineUpdate(input: ByteArray, offset: Int, len: Int) = mac().update(input, offset, len)
        override fun engineDoFinal(): ByteArray = mac().doFinal()
        override fun engineReset() {
            inner?.reset()
        }
    }

    private companion object {
        const val AES = "AES"
        const val HMAC = "HmacSHA256"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
