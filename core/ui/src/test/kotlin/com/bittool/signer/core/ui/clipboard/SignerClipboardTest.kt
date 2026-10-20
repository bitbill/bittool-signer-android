package com.bittool.signer.core.ui.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/** iOS `Clipboard.copy` (`Design/DesignSystem.swift:292-298`): public text only, marked sensitive, gone after 120 s. */
@RunWith(RobolectricTestRunner::class)
class SignerClipboardTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: ClipboardManager get() = context.getSystemService(ClipboardManager::class.java)

    private fun currentText(): String? = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    @Before
    @After
    fun reset() = SignerClipboard.resetForTesting()

    /** What Android 10+ returns to an app without a focused window: no description, as if the clipboard were empty. */
    private fun goToBackground() {
        SignerClipboard.describe = { null }
    }

    private fun returnToForeground() {
        SignerClipboard.describe = { it.primaryClipDescription }
    }

    @Test
    fun aCopyIsMarkedSensitiveAndExpiresAfterTwoMinutes() {
        SignerClipboard.copy(context, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu")
        val clip = assertNotNull(manager.primaryClip)
        assertEquals("BitTool Signer", clip.description.label)
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        ShadowLooper.idleMainLooper(119, TimeUnit.SECONDS)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", currentText())
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        assertFalse(currentText()?.isNotEmpty() == true, "the copy expired")
        assertFalse(SignerClipboard.hasPending)
    }

    @Test
    fun aNewerCopyByTheUserIsNeverCleared() {
        SignerClipboard.copy(context, "0x9858EfFD232B4033E47d90003D41EC34EcaEda94")
        manager.setPrimaryClip(ClipData.newPlainText("notes", "my own text"))
        ShadowLooper.idleMainLooper(121, TimeUnit.SECONDS)
        assertEquals("my own text", currentText())
        assertFalse(SignerClipboard.hasPending)
    }

    @Test
    fun aSecondCopyRestartsTheTimer() {
        SignerClipboard.copy(context, "first")
        ShadowLooper.idleMainLooper(100, TimeUnit.SECONDS)
        SignerClipboard.copy(context, "second")
        ShadowLooper.idleMainLooper(100, TimeUnit.SECONDS)
        assertEquals("second", currentText())
        ShadowLooper.idleMainLooper(21, TimeUnit.SECONDS)
        assertFalse(currentText()?.isNotEmpty() == true)
    }

    /**
     * The usual flow: copy an address, switch to the watch-only wallet to paste it. The deadline passes while the app
     * cannot read the clipboard; the clear must survive that and happen once the app is back and focused.
     */
    @Test
    fun aCopyThatExpiresInTheBackgroundIsClearedOnTheNextForeground() {
        SignerClipboard.copy(context, "TJRabPrwbZy45sbavfcjinPJC18kjpRTv8")
        goToBackground()
        ShadowLooper.idleMainLooper(121, TimeUnit.SECONDS)
        assertEquals("TJRabPrwbZy45sbavfcjinPJC18kjpRTv8", currentText(), "unverifiable from the background: left alone")
        assertTrue(SignerClipboard.hasPending, "the clear is still pending")
        // Resumed, but the window has no focus yet: nothing readable, still pending.
        SignerClipboard.onForeground()
        assertTrue(SignerClipboard.hasPending)
        // Focus arrives just after resume; the deferred check clears the expired copy.
        returnToForeground()
        ShadowLooper.idleMainLooper(SignerClipboard.FOCUS_SETTLE_MILLIS, TimeUnit.MILLISECONDS)
        assertFalse(currentText()?.isNotEmpty() == true, "cleared on return")
        assertFalse(SignerClipboard.hasPending)
    }

    @Test
    fun whatTheUserCopiedElsewhereMeanwhileSurvivesTheReturn() {
        SignerClipboard.copy(context, "0x9858EfFD232B4033E47d90003D41EC34EcaEda94")
        goToBackground()
        ShadowLooper.idleMainLooper(121, TimeUnit.SECONDS)
        manager.setPrimaryClip(ClipData.newPlainText("browser", "a note"))
        returnToForeground()
        SignerClipboard.onForeground()
        assertEquals("a note", currentText())
        assertFalse(SignerClipboard.hasPending)
    }

    /** iOS expires on the wall clock; a `Handler` timer alone would wait for two minutes of awake time. */
    @Test
    fun timeTheDeviceSpentAsleepCountsTowardsTheExpiry() {
        var now = 1_000_000L
        SignerClipboard.clock = { now }
        SignerClipboard.copy(context, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu")
        // Ten minutes asleep: elapsed real time moves on, uptime (the Handler's clock) does not.
        now += 10 * 60_000L
        SignerClipboard.onForeground()
        assertFalse(currentText()?.isNotEmpty() == true, "expired while asleep")
        assertFalse(SignerClipboard.hasPending)
    }

    @Test
    fun aReturnBeforeTheDeadlineKeepsTheRemainingTime() {
        var now = 1_000_000L
        SignerClipboard.clock = { now }
        SignerClipboard.copy(context, "first")
        now += 30_000L
        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS)
        SignerClipboard.onForeground()
        assertEquals("first", currentText())
        now += 89_000L
        ShadowLooper.idleMainLooper(89, TimeUnit.SECONDS)
        assertEquals("first", currentText(), "not yet due")
        now += 2_000L
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        assertFalse(currentText()?.isNotEmpty() == true, "due 120 s after the copy, not 120 s after the return")
    }
}
