package com.bittool.signer.core.crypto.bip39

import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText

/**
 * The ten BIP39 wordlists (vault-crypto.md §13.1, iOS `Models/BIP39Wordlist.swift`). Files are byte-identical copies
 * of bitcoin/bips shipped as Android assets `core/crypto/src/main/assets/bip39/bip39-<fileName>.txt` (gaps.md G8; JVM
 * tests read them from that source path, Robolectric tests through `AssetManager`) and
 * verified against [sha256] on load; a failed check makes the list unavailable (detection skips it). Declaration order
 * is the detection order (English first, so the common case never loads the others). [displayName] is an endonym,
 * never translated. Latin lists ([matchesWithoutAccents]) also match accent-insensitively.
 */
enum class Bip39Language(val fileName: String, val displayName: String, val sha256: String, val matchesWithoutAccents: Boolean) {
    English("english", "English", "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", true),
    ChineseSimplified("chinese_simplified", "简体中文", "5c5942792bd8340cb8b27cd592f1015edf56a8c5b26276ee18a482428e7c5726", false),
    ChineseTraditional("chinese_traditional", "繁體中文", "417b26b3d8500a4ae3d59717d7011952db6fc2fb84b807f3f94ac734e89c1b5f", false),
    Japanese("japanese", "日本語", "2eed0aef492291e061633d7ad8117f1a2b03eb80a29d0e4e3117ac2528d05ffd", false),
    Korean("korean", "한국어", "9e95f86c167de88f450f0aaf89e87f6624a57f973c67b516e338e8e8b8897f60", false),
    Spanish("spanish", "Español", "46846a5a0139d1e3cb77293e521c2865f7bcdb82c44e8d0a06a2cd0ecba48c0b", true),
    French("french", "Français", "ebc3959ab7801a1df6bac4fa7d970652f1df76b683cd2f4003c941c63d517e59", true),
    Italian("italian", "Italiano", "d392c49fdb700a24cd1fceb237c1f65dcc128f6b34a8aacb58b59384b5c648c2", true),
    Czech("czech", "Čeština", "7e80e161c3e93d9554c2efb78d4e3cebf8fc727e9c52e03b83b94406bdcc95fc", true),
    Portuguese("portuguese", "Português", "2685e9c194c82ae67e10ba59d9ea5345a23dc093e92276fc5361f6667d79cd3f", true);

    /** Asset path of the wordlist file (`context.assets.open(assetPath)`). */
    val assetPath: String get() = "bip39/bip39-$fileName.txt"

    val isChinese: Boolean get() = this == ChineseSimplified || this == ChineseTraditional
}

/**
 * A valid reading of a phrase: [phrase] is the NFC words of [language] joined by single spaces — what the vault stores
 * and the seed is derived from. Holds recovery words: [toString] is redacted, never log or save it.
 */
data class Bip39Canonical(val phrase: String, val language: Bip39Language) {
    val words: List<String> get() = phrase.split(' ')
    val wordCount: Int get() = words.size
    override fun toString(): String = "Bip39Canonical(<redacted>, $language)"
}

/** Why a phrase is not accepted (vault-crypto.md §13.4); [text] is an iOS catalogue string. */
sealed class Bip39Error(text: UiText) : SignerException(text) {
    /** No tokens. */
    class Empty : Bip39Error(UiText.Ios("Enter your recovery phrase."))

    /** A token count outside {12, 15, 18, 21, 24}; singular text for 1 (separate keys, never `<plurals>`). */
    class UnsupportedLength(val count: Int) : Bip39Error(
        if (count == 1) UiText.Ios("Invalid recovery phrase: it has 1 word. Recovery phrases have 12, 15, 18, 21 or 24 words.")
        else UiText.Ios("Invalid recovery phrase: it has %lld words. Recovery phrases have 12, 15, 18, 21 or 24 words.", listOf(count)),
    )

    /**
     * Tokens not in the best-matching list. The message names the first 3 in “ ” joined by ", "; singular wording is
     * chosen by the total count of [words] (not by how many are listed). [words] are user input: never log them.
     */
    class UnknownWords(val words: List<String>) : Bip39Error(
        UiText.Ios(
            if (words.size == 1) "Invalid recovery phrase: %@ is not in the BIP39 word list." else "Invalid recovery phrase: %@ are not in the BIP39 word list.",
            listOf(words.take(3).joinToString(", ") { "“$it”" }),
        ),
    )

    /** Every token is a word, but no reading passes the checksum. */
    class Checksum : Bip39Error(UiText.Ios("Invalid recovery phrase. Check the words and their order."))

    /** `canonicalize` only (setup shows a language picker instead): several valid readings. */
    class Ambiguous(val options: List<Bip39Canonical>) :
        Bip39Error(UiText.Ios("These words are a valid recovery phrase in more than one language. Choose the language of your written backup."))
}

/**
 * Live analysis of the import editor text (vault-crypto.md §13.5, iOS `RecoveryPhraseDraft`). Computed once per text
 * change in the setup ViewModel on `Dispatchers.Default`, never inside composition. Holds words: never log it.
 */
data class RecoveryPhraseDraft(
    /** Tokens of the text (numbering dropped, Han runs split). */
    val words: List<String>,
    /** The detected language, or null when nothing matched. */
    val language: Bip39Language?,
    /** Tokens not in the chosen list (the status line shows the first 2 as `Unknown: “a”, “b”`). */
    val unknownWords: List<String>,
    /** The last token when the text ends inside a word (no trailing separator), else null. */
    val partialWord: String?,
    /** The partial word is not (yet) a complete word of the chosen list. */
    val trailingWordIncomplete: Boolean,
) {
    val hasSupportedLength: Boolean get() = words.size in setOf(12, 15, 18, 21, 24)

    /** Supported length, no unknown word, no incomplete trailing word. The checksum is verified on Continue. */
    val looksComplete: Boolean get() = hasSupportedLength && unknownWords.isEmpty() && !trailingWordIncomplete

    override fun toString(): String = "RecoveryPhraseDraft(words=${words.size}, language=$language, unknown=${unknownWords.size})"

    companion object {
        val Empty = RecoveryPhraseDraft(emptyList(), null, emptyList(), null, false)
    }
}

/** One loaded wordlist (vault-crypto.md §13.2). Thread-safe, immutable. */
interface Bip39Wordlist {
    val language: Bip39Language

    /** False when the file failed its hash, count (2,048) or uniqueness checks: then [words] is empty. */
    val isAvailable: Boolean

    /** The 2,048 words in index order, NFC (display and storage form). */
    val words: List<String>

    /**
     * Index of [token]: by `exactKey = lowercase_ROOT(NFKD(token))` when [exact], else by `key` (exactKey without Mn
     * marks for accent-insensitive lists). First index wins on collision.
     */
    fun index(token: String, exact: Boolean = false): Int?

    fun contains(token: String, exact: Boolean = false): Boolean = index(token, exact) != null

    /**
     * Up to [limit] canonical (NFC, accented) words whose key has `key(prefix)` as a **character-wise** prefix (Swift
     * `hasPrefix`: typed "か" is not a prefix of "が…"), in list order; empty for an empty prefix or unavailable list.
     */
    fun suggestions(prefix: String, limit: Int = 6): List<String>

    fun isPrefix(token: String): Boolean = suggestions(token, 1).isNotEmpty()
}

/**
 * Native BIP39 phrase handling (vault-crypto.md §13, setup-backup.md §11): pure Kotlin, JVM-testable, called on every
 * keystroke. Implemented by W1-crypto (`@Singleton`, lists loaded lazily and cached thread-safely).
 */
interface Bip39 {
    /** The loaded (or unavailable) list of [language]. */
    fun wordlist(language: Bip39Language): Bip39Wordlist

    /**
     * Tokenises typed or pasted text: split on Unicode White_Space, `,` `，` `、`; drop pieces whose NFKD form is only
     * ASCII digits, `.` and `)` (numbering); split an all-Han piece of more than one character into single characters
     * (by code point). Tokens keep their original spelling.
     */
    fun tokens(text: CharSequence): List<String>

    /**
     * The forgiving import parser: exact readings first, then (when there are none or no token has typed accents)
     * accent-insensitive readings of the Latin lists; each reading checksum-valid, duplicates of the same phrase removed.
     * Throws [Bip39Error.Empty], [Bip39Error.UnsupportedLength], [Bip39Error.Checksum] or [Bip39Error.UnknownWords].
     * More than one result means the person must choose the language ("Which language is your backup in?").
     */
    fun interpretations(text: CharSequence): List<Bip39Canonical>

    /** Exactly one interpretation, else [Bip39Error.Ambiguous] (or the [interpretations] errors). */
    fun canonicalize(text: CharSequence): Bip39Canonical

    /**
     * For phrases the app produced or stored (generated, accepted, scanned unedited): exact spellings only, first valid
     * reading in detection order, else [Bip39Error.Checksum] / [Bip39Error.UnsupportedLength] / [Bip39Error.Empty].
     */
    fun validatedCanonical(phrase: CharSequence): Bip39Canonical

    /** Live analysis for the import status line and suggestion bar. */
    fun draft(text: CharSequence): RecoveryPhraseDraft

    /**
     * Standard SeedQR (English only): the trimmed text is 48/60/72/84/96 ASCII digits, each 4-digit group < 2048 → the
     * words; else null. No checksum check here (Continue checks it). Compact (binary) SeedQR is unsupported.
     */
    fun wordsFromSeedQr(text: String): List<String>?

    companion object {
        val SUPPORTED_LENGTHS: Set<Int> = setOf(12, 15, 18, 21, 24)
    }
}
