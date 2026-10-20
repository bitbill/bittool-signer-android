package com.bittool.signer.core.model

import kotlin.random.Random

/** Supported BIP39 phrase lengths (`BIP39.supportedLengths`). */
val Bip39SupportedLengths: Set<Int> = setOf(12, 15, 18, 21, 24)

/**
 * Splits a recovery phrase across three cards so that any two restore it (models-state.md §7, iOS
 * `Models/RecoveryCards.swift`). The layout depends only on the word count: deterministic, not secret, identical on
 * every device.
 */
object RecoveryCards {
    const val COUNT = 3

    /** The card (0, 1 or 2) that leaves out the word at zero-based [position]. */
    fun cardOmitting(position: Int): Int = (position / COUNT + position % COUNT) % COUNT

    fun shows(position: Int, card: Int): Boolean = cardOmitting(position) != card

    /** Zero-based positions [card] leaves out, ascending (shown 1-based in "Leaves out words %@"). */
    fun omittedPositions(card: Int, wordCount: Int): List<Int> = (0 until wordCount).filter { cardOmitting(it) == card }

    /** Bits an attacker must still guess with one card: 11 per missing word, minus one checksum bit per three words. */
    fun remainingEntropyBits(wordCount: Int): Int = (wordCount / COUNT) * 11 - wordCount / 3

    /** 12 words (40 bits) and 15 words (50 bits) leave too little to guess with one card. */
    fun singleCardIsWeak(wordCount: Int): Boolean = remainingEntropyBits(wordCount) < 60
}

/** iOS `BackupVerification.GenerationError.invalidPhraseLength`. */
class BackupCheckLengthException : SignerException(UiText.Ios("A backup check requires a supported BIP39 phrase length."))

/**
 * The in-memory fill-in backup check (models-state.md §8, iOS `Models/BackupVerification.swift`). Immutable: every
 * change returns a new instance, so it can sit in a `StateFlow`. Its chips are real phrase words: [toString] is
 * redacted and it must never be saved (`SavedStateHandle`, `rememberSaveable`) or logged.
 */
class BackupVerification private constructor(
    val wordCount: Int,
    /** Blank positions, zero-based, ascending. */
    val positions: List<Int>,
    /** The blanks' words in a shuffled order that is never the answer order (unless every blank holds one word). */
    val chips: List<Chip>,
    /** Blank position → placed chip ID. */
    val placements: Map<Int, Int>,
    private val selectedPosition: Int?,
    private val correctWords: Map<Int, String>,
) {
    /** [id] is the display order; it says nothing about where the word belongs. */
    data class Chip(val id: Int, val word: String) {
        override fun toString(): String = "Chip($id, <redacted>)"
    }

    /** Where the next chip goes: the chosen blank if still empty, else the first empty blank, else null. */
    val activePosition: Int?
        get() {
            selectedPosition?.let { if (placements[it] == null) return it }
            return positions.firstOrNull { placements[it] == null }
        }

    val isComplete: Boolean get() = placements.size == positions.size

    /** Every blank holds the right **word** (repeated words are interchangeable). */
    val isCorrect: Boolean get() = isComplete && positions.all { word(it) == correctWords[it] }

    fun isBlank(position: Int): Boolean = correctWords.containsKey(position)

    /** The word placed at [position], or null. */
    fun word(position: Int): String? = placements[position]?.let { id -> chips.firstOrNull { it.id == id }?.word }

    fun isPlaced(chip: Chip): Boolean = placements.values.contains(chip.id)

    /** Puts chip [chipId] into the active blank; null when there is no active blank, the ID is unknown or already used. */
    fun place(chipId: Int): BackupVerification? {
        val position = activePosition ?: return null
        if (chips.none { it.id == chipId } || placements.values.contains(chipId)) return null
        return copy(placements = placements + (position to chipId), selectedPosition = null)
    }

    /** Makes an empty blank the next one to fill; ignored for masked positions and filled blanks. */
    fun select(position: Int): BackupVerification =
        if (!isBlank(position) || placements[position] != null) this else copy(selectedPosition = position)

    /** Takes the word out of a blank and makes it the next one to fill; ignored for masked positions. */
    fun clear(position: Int): BackupVerification =
        if (!isBlank(position)) this else copy(placements = placements - position, selectedPosition = position)

    /** Removes every placement and the selection (wrong answer, background, screenshot). */
    fun resetPlacements(): BackupVerification = copy(placements = emptyMap(), selectedPosition = null)

    private fun copy(placements: Map<Int, Int> = this.placements, selectedPosition: Int? = this.selectedPosition) =
        BackupVerification(wordCount, positions, chips, placements, selectedPosition, correctWords)

    override fun equals(other: Any?): Boolean = other is BackupVerification && other.wordCount == wordCount &&
        other.positions == positions && other.chips == chips && other.placements == placements &&
        other.selectedPosition == selectedPosition
    override fun hashCode(): Int = (positions.hashCode() * 31 + placements.hashCode()) * 31 + (selectedPosition ?: -1)
    override fun toString(): String = "BackupVerification(words=$wordCount, blanks=$positions, placed=${placements.size})"

    companion object {
        /** Four blanks for 12–15 words, five for 18–24. */
        fun blankCount(wordCount: Int): Int = if (wordCount >= 18) 5 else 4

        /**
         * A new check over [words] using [random] (production: `SecureRandom().asKotlinRandom()`; tests: a seeded
         * generator). Throws [BackupCheckLengthException] unless the length is a supported BIP39 length.
         */
        fun create(words: List<String>, random: Random): BackupVerification {
            if (words.size !in Bip39SupportedLengths) throw BackupCheckLengthException()
            val positions = words.indices.shuffled(random).take(blankCount(words.size)).sorted()
            val correct = positions.associateWith { words[it] }
            val answer = positions.map { words[it] }
            var order = answer.indices.toList()
            if (answer.toSet().size > 1) {
                do {
                    order = order.shuffled(random)
                } while (order.map { answer[it] } == answer)
            }
            val chips = order.mapIndexed { i, from -> Chip(i, answer[from]) }
            return BackupVerification(words.size, positions, chips, emptyMap(), null, correct)
        }
    }
}
