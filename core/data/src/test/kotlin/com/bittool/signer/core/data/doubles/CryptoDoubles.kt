package com.bittool.signer.core.data.doubles

import android.security.keystore.UserNotAuthenticatedException
import com.bittool.signer.core.crypto.Bip39Seed
import com.bittool.signer.core.crypto.SecureRandomSource
import com.bittool.signer.core.crypto.bip39.Bip39
import com.bittool.signer.core.crypto.bip39.Bip39Canonical
import com.bittool.signer.core.crypto.bip39.Bip39Error
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.crypto.bip39.Bip39Wordlist
import com.bittool.signer.core.crypto.bip39.RecoveryPhraseDraft
import com.bittool.signer.core.crypto.vault.JournalCipher
import com.bittool.signer.core.crypto.vault.PublicDataKey
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.QrCodeEncoder
import com.bittool.signer.core.model.QrEncodingException
import com.bittool.signer.core.model.QrMatrix
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.util.Hex
import com.bittool.signer.core.model.util.constantTimeEquals
import com.bittool.signer.core.model.util.isUnicodeWhiteSpace
import com.bittool.signer.core.model.util.utf8Bytes
import com.bittool.signer.core.model.util.utf8Size
import com.bittool.signer.core.model.util.wipe
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/*
 * JVM test doubles of the `:core:crypto` and `:core:scanner` interfaces `DefaultSignerStore` consumes
 * (ARCHITECTURE.md §3 W1-data: "an English-only Bip39 and a Mac("HmacSHA512") Bip39Seed in test code, a fake
 * encoder"). They follow the KDoc contracts closely enough for the store's JVM tests (StoreFixture); the instrumented
 * tests run on the real `:core:crypto` / `:core:scanner` implementations instead (androidTest `RealStoreFixture`), so
 * these doubles live in `src/test` only.
 */

/** `SecureRandomSource` over `java.security.SecureRandom` (vault-crypto.md §3). */
class JvmSecureRandomSource : SecureRandomSource {
    private val random = SecureRandom()

    override fun bytes(count: Int): ByteArray {
        if (count !in 1..65_536) throw VaultError.RandomFailure()
        return ByteArray(count).also(random::nextBytes)
    }

    override fun entropyHex(wordCount: Int): CharArray {
        if (wordCount !in Bip39.SUPPORTED_LENGTHS) throw VaultError.InvalidSecret()
        val bytes = bytes(wordCount / 3 * 4)
        return try {
            Hex.encodeToChars(bytes)
        } finally {
            bytes.wipe()
        }
    }

    override fun asKotlinRandom(): Random = random.asKotlinRandom()
}

/**
 * The native BIP39 seed (vault-crypto.md §13.7): PBKDF2-HMAC-SHA512 over raw UTF-8 bytes, 2,048 iterations, 64 bytes,
 * phrase = whitespace-collapsed lowercase NFKD, salt = NFKD("mnemonic" + passphrase). Reproduces the published vectors.
 */
class HmacBip39Seed : Bip39Seed {
    /** Sees every seed handed out, before it is returned (tests check it is wiped, never its contents). */
    @Volatile
    var onProduced: ((CharArray) -> Unit)? = null

    override fun hex(mnemonic: SecretText, passphrase: SecretText): CharArray = produce(mnemonic, passphrase).also { onProduced?.invoke(it) }

    private fun produce(mnemonic: SecretText, passphrase: SecretText): CharArray {
        val normalized = Normalizer.normalize(mnemonic.view(), Normalizer.Form.NFKD).lowercase(Locale.ROOT)
        val phrase = normalized.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
        if (phrase.isEmpty() || phrase.utf8Size() > 1_024 || passphrase.utf8Size() > 1_024) throw VaultError.InvalidSecret()
        val password = utf8Bytes(phrase.toCharArray())
        val salt = Normalizer.normalize("mnemonic" + String(passphrase.chars()), Normalizer.Form.NFKD).encodeToByteArray()
        val mac = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(password, "HmacSHA512")) }
        password.wipe()
        // One PBKDF2 block: HMAC-SHA512 already yields the 64 bytes asked for (RFC 8018 §5.2, block index 1).
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val out = u.copyOf()
        repeat(2_047) {
            val next = mac.doFinal(u)
            u.wipe()
            u = next
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        u.wipe()
        return try {
            Hex.encodeToChars(out)
        } finally {
            out.wipe()
        }
    }

    private companion object {
        val WHITESPACE = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\u0085]+")
    }
}

/**
 * English-only BIP39 (the store needs `validatedCanonical`; the other members are implemented for English so the
 * double is total). The wordlist is the pinned bitcoin/bips file (SHA-256 checked) from the shared test resources.
 */
class EnglishBip39 : Bip39 {
    private val english: Bip39Wordlist by lazy { loadEnglish() }

    override fun wordlist(language: Bip39Language): Bip39Wordlist =
        if (language == Bip39Language.English) english else UnavailableWordlist(language)

    override fun tokens(text: CharSequence): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (isUnicodeWhiteSpace(cp) || cp == ','.code || cp == '，'.code || cp == '、'.code) {
                if (current.isNotEmpty()) out += current.toString()
                current.clear()
            } else {
                current.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out.filterNot { token -> Normalizer.normalize(token, Normalizer.Form.NFKD).all { it.isDigit() || it == '.' || it == ')' } }
    }

    override fun interpretations(text: CharSequence): List<Bip39Canonical> = listOf(validatedCanonical(text))

    override fun canonicalize(text: CharSequence): Bip39Canonical = validatedCanonical(text)

    override fun validatedCanonical(phrase: CharSequence): Bip39Canonical {
        val words = tokens(phrase)
        if (words.isEmpty()) throw Bip39Error.Empty()
        if (words.size !in Bip39.SUPPORTED_LENGTHS) throw Bip39Error.UnsupportedLength(words.size)
        val indexes = words.map { english.index(it, exact = true) ?: throw Bip39Error.Checksum() }
        if (!checksumValid(indexes)) throw Bip39Error.Checksum()
        return Bip39Canonical(indexes.joinToString(" ") { english.words[it] }, Bip39Language.English)
    }

    override fun draft(text: CharSequence): RecoveryPhraseDraft {
        val words = tokens(text)
        val unknown = words.filter { english.index(it) == null }
        return RecoveryPhraseDraft(words, Bip39Language.English.takeIf { unknown.size < words.size }, unknown, null, false)
    }

    override fun wordsFromSeedQr(text: String): List<String>? {
        val digits = text.trim()
        if (digits.length !in setOf(48, 60, 72, 84, 96) || !digits.all { it in '0'..'9' }) return null
        return digits.chunked(4).map { chunk -> chunk.toInt().takeIf { it < 2048 }?.let { english.words[it] } ?: return null }
    }

    private fun checksumValid(indexes: List<Int>): Boolean {
        val totalBits = indexes.size * 11
        val checksumBits = totalBits / 33
        val entropyBits = totalBits - checksumBits
        val bits = BooleanArray(totalBits) { i -> (indexes[i / 11] shr (10 - i % 11)) and 1 == 1 }
        val entropy = ByteArray(entropyBits / 8) { b -> (0 until 8).fold(0) { acc, k -> (acc shl 1) or if (bits[b * 8 + k]) 1 else 0 }.toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        entropy.wipe()
        return (0 until checksumBits).all { k -> bits[entropyBits + k] == ((hash[k / 8].toInt() shr (7 - k % 8)) and 1 == 1) }
    }

    private fun loadEnglish(): Bip39Wordlist {
        val bytes = checkNotNull(EnglishBip39::class.java.getResourceAsStream("/bip39/bip39-english.txt")) { "bip39-english.txt missing" }
            .use { it.readBytes() }
        check(Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes)) == Bip39Language.English.sha256) { "English wordlist hash" }
        val words = bytes.decodeToString().split('\n').filter { it.isNotEmpty() }
        check(words.size == 2048)
        return ListWordlist(Bip39Language.English, words)
    }

    private class ListWordlist(override val language: Bip39Language, override val words: List<String>) : Bip39Wordlist {
        private val byWord = words.withIndex().associate { (i, w) -> w to i }
        override val isAvailable = true
        override fun index(token: String, exact: Boolean): Int? = byWord[Normalizer.normalize(token, Normalizer.Form.NFKD).lowercase(Locale.ROOT)]
        override fun suggestions(prefix: String, limit: Int): List<String> =
            if (prefix.isEmpty()) emptyList() else words.filter { it.startsWith(prefix.lowercase(Locale.ROOT)) }.take(limit)
    }

    private class UnavailableWordlist(override val language: Bip39Language) : Bip39Wordlist {
        override val isAvailable = false
        override val words: List<String> = emptyList()
        override fun index(token: String, exact: Boolean): Int? = null
        override fun suggestions(prefix: String, limit: Int): List<String> = emptyList()
    }
}

/** A `PublicDataKey` over a JCA `HmacSHA256` key (the Keystore key is non-exportable; the MAC is the same function). */
class HmacPublicDataKey(private val key: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)) : PublicDataKey {
    /**
     * The phone counts as locked for Keystore (face unlock or Smart Lock on Android 12–13): every use throws what the
     * Keystore key does, `DeviceKeyUnavailable` caused by `UserNotAuthenticatedException` (Robolectric tests only).
     */
    @Volatile
    var locked: Boolean = false

    override fun mac(message: ByteArray): ByteArray {
        if (locked) throw VaultError.DeviceKeyUnavailable(UserNotAuthenticatedException())
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)
    }

    override fun verify(message: ByteArray, tag: ByteArray): Boolean = constantTimeEquals(mac(message), tag)
}

/**
 * The journal envelope of the JournalCipher KDoc (`"BTSR" ‖ 0x01 ‖ iv(12) ‖ ciphertext ‖ tag(16)`, header as AAD) with
 * an in-memory AES-256 key standing in for the Keystore key. [loseKey] models a lost or invalidated Keystore key.
 */
class AesGcmJournalCipher : JournalCipher {
    @Volatile
    private var key = newKey()
    private val random = SecureRandom()

    /** How many times [seal] ran. */
    val seals = AtomicInteger()

    /** Set by [loseKeyForGood] until the next [seal] creates a new key (what the real cipher's Keystore proof shows). */
    @Volatile
    private var keyProvablyGone = false

    /** How many times [discardLostKey] was asked. */
    val lossChecks = AtomicInteger()

    /** A different key: the journal no longer opens, and nothing proves that the old key is gone (a damaged journal). */
    fun loseKey() {
        key = newKey()
    }

    /** The key is gone for good (the screen lock was removed): the journal no longer opens and [discardLostKey] says so. */
    fun loseKeyForGood() {
        key = newKey()
        keyProvablyGone = true
    }

    override fun discardLostKey(): Boolean {
        lossChecks.incrementAndGet()
        return keyProvablyGone
    }

    override fun seal(plaintext: ByteArray): ByteArray {
        seals.incrementAndGet()
        keyProvablyGone = false
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(HEADER)
        }
        return HEADER + iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < HEADER.size + 12 + 16 || !sealed.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            throw VaultError.AuthenticationFailed()
        }
        val iv = sealed.copyOfRange(HEADER.size, HEADER.size + 12)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(HEADER)
            }.doFinal(sealed, HEADER.size + 12, sealed.size - HEADER.size - 12)
        } catch (e: AEADBadTagException) {
            throw VaultError.AuthenticationFailed()
        }
    }

    private fun newKey() = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")

    companion object {
        val HEADER = byteArrayOf('B'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte(), 1)
    }
}

/**
 * A deterministic `QrCodeEncoder` stand-in (the ZXing one belongs to W1-scanner): a 21×21 matrix derived from the
 * text's SHA-256. [failing] texts throw like an oversized frame would; [encoded] counts calls.
 */
class FakeQrCodeEncoder(@Volatile var failing: Set<String> = emptySet()) : QrCodeEncoder {
    val encoded = AtomicInteger()

    override fun encode(text: String): QrMatrix {
        encoded.incrementAndGet()
        if (text in failing) throw QrEncodingException("Too large for a QR symbol")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
        return QrMatrix(21, BooleanArray(21 * 21) { i -> (digest[i % 32].toInt() shr (i % 8)) and 1 == 1 })
    }
}
