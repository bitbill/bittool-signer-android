package com.bittool.signer.core.model

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Ports of `RecoveryCardsTests.swift` (layout rules) and `BackupVerificationTests.swift`. */
class BackupTest {
    /** The first 24 English BIP39 words: public, and the check never needs a checksum. */
    private val words = listOf(
        "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract", "absurd", "abuse", "access", "accident",
        "account", "accuse", "achieve", "acid", "acoustic", "acquire", "across", "act", "action", "actor", "actress", "actual",
    )
    private val distinctPhrase = words.take(12)

    @Test
    fun anyTwoCardsRestoreEveryWordAndOneCardLacksAThird() {
        for (wordCount in listOf(12, 15, 18, 21, 24)) {
            val positions = (0 until wordCount).toSet()
            for (card in 0 until RecoveryCards.COUNT) {
                val omitted = RecoveryCards.omittedPositions(card, wordCount)
                assertEquals(wordCount / 3, omitted.size)
                assertEquals(omitted.sorted(), omitted)
                assertTrue(omitted.none { RecoveryCards.shows(it, card) })
                assertTrue((positions - omitted.toSet()).all { RecoveryCards.shows(it, card) })
            }
            for (first in 0 until 3) for (second in 0 until 3) if (first != second) {
                assertEquals(positions, positions.filter { RecoveryCards.shows(it, first) || RecoveryCards.shows(it, second) }.toSet())
            }
            assertTrue(positions.all { p -> (0 until 3).count { !RecoveryCards.shows(p, it) } == 1 })
        }
    }

    @Test
    fun eachRowOfThreeLeavesOutOneWordPerCard() {
        for (row in 0 until 8) assertEquals(setOf(0, 1, 2), (0 until 3).map { RecoveryCards.cardOmitting(row * 3 + it) }.toSet())
        assertEquals(listOf(0, 5, 7, 9, 14, 16, 18, 23), RecoveryCards.omittedPositions(0, 24))
        assertEquals(listOf(1, 3, 8, 10), RecoveryCards.omittedPositions(1, 12))
        assertEquals(listOf(2, 4, 6, 11), RecoveryCards.omittedPositions(2, 12))
    }

    @Test
    fun shortPhrasesAreFlaggedAsWeakForSplitBackups() {
        assertEquals(40, RecoveryCards.remainingEntropyBits(12))
        assertEquals(50, RecoveryCards.remainingEntropyBits(15))
        assertEquals(60, RecoveryCards.remainingEntropyBits(18))
        assertEquals(80, RecoveryCards.remainingEntropyBits(24))
        assertTrue(RecoveryCards.singleCardIsWeak(12))
        assertTrue(RecoveryCards.singleCardIsWeak(15))
        assertFalse(RecoveryCards.singleCardIsWeak(18))
        assertFalse(RecoveryCards.singleCardIsWeak(24))
    }

    @Test
    fun supportedLengthsBlankRandomPositionsAndOfferExactlyTheirWords() {
        for (length in listOf(12, 15, 18, 21, 24)) {
            val phrase = words.take(length)
            val challenge = BackupVerification.create(phrase, SplitMix64(length.toLong()))
            assertEquals(length, challenge.wordCount)
            assertEquals(if (length >= 18) 5 else 4, challenge.positions.size)
            assertEquals(challenge.positions.toSet().size, challenge.positions.size)
            assertEquals(challenge.positions.sorted(), challenge.positions)
            assertTrue(challenge.positions.all { it in phrase.indices })
            assertEquals(challenge.positions.map { phrase[it] }.sorted(), challenge.chips.map { it.word }.sorted())
            assertNotEquals(challenge.positions.map { phrase[it] }, challenge.chips.map { it.word })
            assertEquals(challenge.chips.indices.toList(), challenge.chips.map { it.id })
            assertEquals(challenge.positions.first(), challenge.activePosition)
            assertFalse(challenge.isComplete)
            assertFalse(challenge.isCorrect)
        }
    }

    @Test
    fun placingInOrderPassesAndAnyOtherOrderFails() {
        var challenge = BackupVerification.create(distinctPhrase, SplitMix64(83))
        for (position in challenge.positions) {
            val chip = challenge.chips.first { it.word == distinctPhrase[position] && !challenge.isPlaced(it) }
            assertEquals(position, challenge.activePosition)
            challenge = assertNotNull(challenge.place(chip.id))
        }
        assertTrue(challenge.isComplete)
        assertTrue(challenge.isCorrect)
        assertNull(challenge.activePosition)
        assertNull(challenge.place(0), "nothing more can be placed once every blank is filled")

        val middle = challenge.positions[1]
        challenge = challenge.clear(middle)
        assertEquals(middle, challenge.activePosition)
        assertFalse(challenge.isComplete)

        challenge = challenge.resetPlacements()
        val answer = challenge.positions.map { p -> challenge.chips.first { it.word == distinctPhrase[p] }.id }
        val swapped = answer.toMutableList().also { val t = it[0]; it[0] = it[1]; it[1] = t }
        for (id in swapped) challenge = challenge.place(id) ?: challenge
        assertTrue(challenge.isComplete)
        assertFalse(challenge.isCorrect)
        challenge = challenge.resetPlacements()
        assertTrue(challenge.placements.isEmpty())
        assertEquals(challenge.positions.first(), challenge.activePosition)
    }

    @Test
    fun choosingABlankDirectsTheNextWordThere() {
        var challenge = BackupVerification.create(distinctPhrase, SplitMix64(29))
        val last = challenge.positions.last()
        challenge = challenge.select(last)
        assertEquals(last, challenge.activePosition)
        challenge = assertNotNull(challenge.place(challenge.chips.first { it.word == distinctPhrase[last] }.id))
        assertEquals(distinctPhrase[last], challenge.word(last))
        assertEquals(challenge.positions.first(), challenge.activePosition, "after placing, the first empty blank is next")
        val masked = distinctPhrase.indices.first { !challenge.isBlank(it) }
        challenge = challenge.select(masked).select(last)
        assertEquals(challenge.positions.first(), challenge.activePosition)
        challenge = challenge.clear(last)
        assertEquals(last, challenge.activePosition)
    }

    @Test
    fun chipsCannotBeUsedTwiceOrInvented() {
        val challenge = assertNotNull(BackupVerification.create(distinctPhrase, SplitMix64(51)).place(0))
        assertNull(challenge.place(0))
        assertNull(challenge.place(-1))
        assertNull(challenge.place(challenge.chips.size))
    }

    @Test
    fun repeatedWordsAreInterchangeable() {
        val phrase = List(11) { "abandon" } + "about"
        for (seed in 0L until 12L) {
            var challenge = BackupVerification.create(phrase, SplitMix64(seed))
            for (position in challenge.positions) {
                val chip = challenge.chips.first { it.word == phrase[position] && !challenge.isPlaced(it) }
                challenge = assertNotNull(challenge.place(chip.id))
            }
            assertTrue(challenge.isCorrect)
        }
    }

    @Test
    fun injectedRandomnessIsRepeatableAndFreshChallengesVary() {
        val a = BackupVerification.create(distinctPhrase, SplitMix64(5))
        val b = BackupVerification.create(distinctPhrase, SplitMix64(5))
        val c = BackupVerification.create(distinctPhrase, SplitMix64(6))
        assertTrue(a.positions == b.positions && a.chips == b.chips)
        assertTrue(a.positions != c.positions || a.chips != c.chips)
        assertFalse(a.toString().contains("abandon"), "a check never prints its words")
    }

    @Test
    fun malformedInputFailsClosed() {
        for (length in listOf(0, 4, 11, 13, 25, 48)) {
            val error = assertFailsWith<BackupCheckLengthException> { BackupVerification.create(List(length) { "abandon" }, Random(1)) }
            assertEquals(UiText.Ios("A backup check requires a supported BIP39 phrase length."), error.text)
        }
    }
}

/** SplitMix64 as in the iOS test fixture: deterministic, never used for app randomness. */
class SplitMix64(seed: Long) : Random() {
    private var state = seed

    private fun nextLong64(): Long {
        state += -0x61c8864680b583ebL // 0x9e3779b97f4a7c15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xbf58476d1ce4e5b9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94d049bb133111eb
        return z xor (z ushr 31)
    }

    override fun nextBits(bitCount: Int): Int = (nextLong64() ushr (64 - bitCount)).toInt()
}
