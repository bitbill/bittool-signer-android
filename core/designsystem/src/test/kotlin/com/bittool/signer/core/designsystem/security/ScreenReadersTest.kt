package com.bittool.signer.core.designsystem.security

import android.os.Build
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** The rule of 2026-11-16 audit M-03: who could read a secret through accessibility below API 34. */
class ScreenReadersTest {
    private val talkBack = EnabledService("TalkBack", preinstalled = true)
    private val stranger = EnabledService("Battery Saver Pro", preinstalled = false)

    @Test
    fun fromApi34ThePlatformKeepsServicesAwayAndNothingIsNeeded() {
        val readers = classifyScreenReaders(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, listOf(stranger), touchExploration = false)
        assertEquals(ScreenReaders.None, readers)
        assertFalse(readers.warn)
        assertFalse(readers.hideSecrets)
    }

    @Test
    fun servicesThatCameWithThePhoneAreTrusted() {
        val readers = classifyScreenReaders(Build.VERSION_CODES.S_V2, listOf(talkBack), touchExploration = true)
        assertEquals(emptyList(), readers.untrusted)
        assertFalse(readers.warn, "a screen reader that came with the phone raises no warning")
        assertFalse(readers.hideSecrets)
    }

    @Test
    fun anInstalledServiceWarnsAndHidesSecretsWhileNoScreenReaderNeedsThem() {
        val readers = classifyScreenReaders(Build.VERSION_CODES.O, listOf(talkBack, stranger), touchExploration = false)
        assertEquals(listOf("Battery Saver Pro"), readers.untrusted)
        assertTrue(readers.warn)
        assertTrue(readers.hideSecrets)
    }

    @Test
    fun withAScreenReaderOnTheWordsStayReadableAndTheWarningIsTheProtection() {
        val readers = classifyScreenReaders(Build.VERSION_CODES.TIRAMISU, listOf(talkBack, stranger), touchExploration = true)
        assertTrue(readers.warn)
        assertFalse(readers.hideSecrets, "TalkBack users still hear their words")
    }

    @Test
    fun namesAreTidiedForTheWarning() {
        val readers = classifyScreenReaders(
            Build.VERSION_CODES.R,
            listOf(
                EnabledService("  Two\n words ", preinstalled = false),
                EnabledService("Two words", preinstalled = false),
                EnabledService("   ", preinstalled = false),
                EnabledService("x".repeat(100), preinstalled = false),
            ),
            touchExploration = false,
        )
        assertEquals(listOf("Two words", "?", "x".repeat(40)), readers.untrusted, "collapsed, merged, never blank, at most 40 characters")
    }
}
