package com.bittool.signer.core.model

import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.utf8Size

/**
 * Wallet password rules (vault-crypto.md §4, iOS `Core/PasswordPolicy.swift`). Passwords are used exactly as typed: no
 * trimming, no normalisation. Categories are evaluated per Unicode scalar; the length is in grapheme clusters
 * (8…64) and at most 1,024 UTF-8 bytes. Takes a [CharSequence] so a `CharArray` can be checked through
 * `CharBuffer.wrap` / [SecretText.view] without a `String` copy.
 */
object PasswordPolicy {
    const val MINIMUM_LENGTH = 8
    const val MAXIMUM_LENGTH = 64
    const val MAXIMUM_UTF8_BYTES = 1_024

    data class Evaluation(
        val isValid: Boolean,
        /** How many of the four categories are present (0–4). */
        val categories: Int,
        val hasUppercase: Boolean,
        val hasLowercase: Boolean,
        val hasNumber: Boolean,
        val hasSymbol: Boolean,
        val hasValidLength: Boolean,
        val containsControl: Boolean,
        /** Grapheme count (for the strength thresholds 12 and 16). */
        val length: Int,
        /** First failing rule's message (shown only through vault errors on iOS). */
        val message: UiText,
    )

    fun evaluate(password: CharSequence): Evaluation {
        var upper = false
        var lower = false
        var number = false
        var symbol = false
        var control = false
        var i = 0
        while (i < password.length) {
            val cp = Character.codePointAt(password, i)
            when (Character.getType(cp).toByte()) {
                Character.UPPERCASE_LETTER, Character.TITLECASE_LETTER -> upper = true
                Character.LOWERCASE_LETTER -> lower = true
                Character.DECIMAL_DIGIT_NUMBER -> number = true
                Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION -> symbol = true
                Character.CONTROL, Character.FORMAT -> control = true
                else -> Unit
            }
            i += Character.charCount(cp)
        }
        val categories = listOf(upper, lower, number, symbol).count { it }
        val length = Graphemes.count(password)
        val validLength = length in MINIMUM_LENGTH..MAXIMUM_LENGTH && password.utf8Size() <= MAXIMUM_UTF8_BYTES
        val valid = validLength && categories >= 3 && !control
        val message = when {
            !validLength -> UiText.Ios("Use 8–64 characters.")
            control -> UiText.Ios("Control characters are not allowed.")
            categories < 3 -> UiText.Ios("Include at least 3 of: uppercase, lowercase, numbers and symbols.")
            else -> UiText.Ios("Password meets the requirements.")
        }
        return Evaluation(valid, categories, upper, lower, number, symbol, validLength, control, length, message)
    }

    /**
     * The strength meter level (vault-crypto.md §5.2): 0 empty; 1 invalid; 4 when ≥ 16 graphemes with all four
     * categories; 3 when ≥ 12 graphemes; else 2. Colours: 1 warning, 2 yellow, 3–4 success.
     */
    fun strength(password: CharSequence): Int {
        if (password.isEmpty()) return 0
        val e = evaluate(password)
        return when {
            !e.isValid -> 1
            e.length >= 16 && e.categories == 4 -> 4
            e.length >= 12 -> 3
            else -> 2
        }
    }

    /** "Passwords match": the confirmation is non-empty and equal (constant-time over the chars). */
    fun matches(password: CharSequence, confirmation: CharSequence): Boolean {
        if (confirmation.isEmpty() || password.length != confirmation.length) return false
        var diff = 0
        for (i in password.indices) diff = diff or (password[i].code xor confirmation[i].code)
        return diff == 0
    }
}
