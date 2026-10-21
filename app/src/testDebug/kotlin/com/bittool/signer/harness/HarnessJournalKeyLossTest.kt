package com.bittool.signer.harness

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.activity.EncryptedReceiptRepository
import com.bittool.signer.core.model.SignerException
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * QA round 3 activity-keyboard-07: a harness path to Activity's restart notice ("Activity Started Again", QA L1,
 * ARCHITECTURE §6 item 19). `bts.uitest.loseJournalKey` loses the run's journal key the way removing the screen lock
 * loses the Keystore key, so the store's own recovery (`ReceiptRepository.startOverIfKeyLost`, which the store calls
 * when a read fails) proves the loss through `FixtureJournalCipher.discardLostKey` and starts the journal again with the
 * notice. A journal that does not open while nothing proves its key lost (`bts.uitest.unreadableJournal`) stays
 * unreadable, as on iOS.
 */
@RunWith(RobolectricTestRunner::class)
class HarnessJournalKeyLossTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val runId = "6B0E3F1A-2C4D-4E5F-8A9B-0C1D2E3F4A5B"
    private val root get() = File(context.cacheDir, "ui-tests/${runId.lowercase()}")
    private val keyFile get() = File(root, "FixtureKeys/${FixtureJournalCipher.KEY_FILE}")

    @Before
    fun setUp() {
        DebugUiTestHarness.resetForTest()
        DebugUiTestHarness.isEmulator = { true }
        DebugUiTestHarness.createsFixtures = false
        root.deleteRecursively()
    }

    @After
    fun tearDown() {
        DebugUiTestHarness.resetForTest()
        root.deleteRecursively()
    }

    private fun launch(vararg switches: String, reset: Boolean = false) = assertNotNull(
        DebugUiTestHarness.configure(
            context,
            Intent().apply {
                putExtra(DebugUiTestHarness.EXTRA_RUN, runId)
                if (reset) putExtra(DebugUiTestHarness.EXTRA_RESET, true)
                switches.forEach { putExtra(it, true) }
            },
        ),
    )

    /** A new process's repository over the run (what `DebugUiTestModule` binds). */
    private fun repository() = EncryptedReceiptRepository(root, FixtureJournalCipher(keyFile), Dispatchers.IO)

    @Test
    fun theSwitchIsTheOneToolsQaUiPySends() {
        assertEquals("bts.uitest.loseJournalKey", DebugUiTestHarness.EXTRA_LOSE_JOURNAL_KEY)
    }

    @Test
    fun aLostJournalKeyStartsTheRunsActivityAgainOnceWithTheNotice() = runTest {
        // The run signed before (a journal sealed under its key), then is launched again with the switch.
        repository().write(emptyList())
        assertTrue(keyFile.isFile)
        launch(LOSE_JOURNAL_KEY)
        assertFalse(keyFile.exists(), "the key is gone for good")

        val lost = repository()
        assertFailsWith<SignerException> { lost.read() }
        assertTrue(lost.startOverIfKeyLost(), "the loss is proven, so the journal starts again")
        assertTrue(lost.hasRestartNotice(), "Activity shows \"Activity Started Again\"")
        assertEquals(emptyList(), lost.read())

        // The next signature seals under a new key, which ends the loss: the journal is never started again.
        lost.write(emptyList())
        assertTrue(keyFile.isFile)
        assertFalse(lost.startOverIfKeyLost())
        lost.acknowledgeRestartNotice()
        val later = repository()
        assertEquals(emptyList(), later.read(), "a later launch without the switch reads the new journal")
        assertFalse(later.startOverIfKeyLost())
        assertFalse(later.hasRestartNotice())
    }

    @Test
    fun aFreshRunWithTheSwitchMeetsAJournalItCanNeverReadAgain() = runTest {
        // `ui.py launch --fixtures 1 --lose-journal-key`: no journal and no key yet.
        val run = launch(LOSE_JOURNAL_KEY, reset = true)
        assertEquals(root, run.root)
        assertTrue(File(root, "receipts.bin").isFile, "a journal the lost key sealed")
        val repository = repository()
        assertFailsWith<SignerException> { repository.read() }
        assertTrue(repository.startOverIfKeyLost())
        assertFalse(File(root, "receipts.bin").exists())
        assertTrue(repository.hasRestartNotice())
    }

    @Test
    fun anUnreadableJournalWhoseKeyIsNotLostStaysUnreadable() = runTest {
        // `--unreadable-journal` on a fresh run: no key file yet, which alone proves nothing.
        launch(UNREADABLE_JOURNAL, reset = true)
        val repository = repository()
        val sealed = File(root, "receipts.bin").readBytes()
        assertFailsWith<SignerException> { repository.read() }
        assertFalse(repository.startOverIfKeyLost(), "no loss is recorded")
        // And with a key in place (the run signed before).
        FixtureJournalCipher(keyFile).seal("[]".toByteArray())
        assertFalse(repository().startOverIfKeyLost())
        assertContentEquals(sealed, File(root, "receipts.bin").readBytes(), "never replaced")
        assertFalse(repository.hasRestartNotice())
    }

    @Test
    fun theLostKeyWinsOverTheUnreadableJournal() = runTest {
        launch(UNREADABLE_JOURNAL, LOSE_JOURNAL_KEY, reset = true)
        assertTrue(repository().startOverIfKeyLost())
    }

    @Test
    fun theFixtureCipherProvesOnlyARecordedLoss() {
        val keyFile = File(context.cacheDir, "fixture-journal-loss/journal.key").apply { parentFile?.deleteRecursively() }
        val journal = File(keyFile.parentFile, "receipts.bin")
        assertFalse(FixtureJournalCipher(keyFile).discardLostKey(), "no key yet: a run that never sealed")
        val cipher = FixtureJournalCipher(keyFile)
        val sealed = cipher.seal("[]".toByteArray())
        assertFalse(cipher.discardLostKey(), "a usable key is not lost")

        journal.writeBytes(sealed)
        FixtureJournalCipher.loseKey(keyFile, journal)
        assertContentEquals(sealed, journal.readBytes(), "an existing journal is kept: the loss takes its records")
        assertFailsWith<VaultError.AuthenticationFailed> { cipher.open(sealed) }
        assertTrue(cipher.discardLostKey())
        assertTrue(cipher.discardLostKey(), "the proof holds until a new key exists")
        // The cipher that used the old key creates a new one, as the Keystore cipher does after a proven loss.
        val next = cipher.seal("[]".toByteArray())
        assertContentEquals("[]".toByteArray(), FixtureJournalCipher(keyFile).open(next))
        assertFalse(cipher.discardLostKey(), "the new key ends the loss")
        assertFalse(FixtureJournalCipher(keyFile).discardLostKey())

        // A key that goes missing without a recorded loss still never orphans the journal.
        keyFile.delete()
        assertFailsWith<VaultError.DeviceKeyUnavailable> { cipher.seal("[]".toByteArray()) }
        assertFalse(cipher.discardLostKey())

        // A recorded loss whose key file is left but is not a key: the remnant is removed, as an invalidated entry is.
        FixtureJournalCipher.loseKey(keyFile, journal)
        keyFile.writeBytes(ByteArray(3))
        val fresh = FixtureJournalCipher(keyFile)
        assertTrue(fresh.discardLostKey())
        assertFalse(keyFile.exists())
        assertContentEquals("[]".toByteArray(), fresh.open(fresh.seal("[]".toByteArray())))
    }

    private companion object {
        const val LOSE_JOURNAL_KEY = "bts.uitest.loseJournalKey"
        const val UNREADABLE_JOURNAL = "bts.uitest.unreadableJournal"
    }
}
