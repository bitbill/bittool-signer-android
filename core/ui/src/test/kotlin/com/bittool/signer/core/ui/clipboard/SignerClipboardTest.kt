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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/** iOS `Clipboard.copy` (`Design/DesignSystem.swift:292-298`): public text only, marked sensitive, gone after 120 s. */
@RunWith(RobolectricTestRunner::class)
class SignerClipboardTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: ClipboardManager get() = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun aCopyIsMarkedSensitiveAndExpiresAfterTwoMinutes() {
        SignerClipboard.copy(context, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu")
        val clip = assertNotNull(manager.primaryClip)
        assertEquals("BitTool Signer", clip.description.label)
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        ShadowLooper.idleMainLooper(119, TimeUnit.SECONDS)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", manager.primaryClip?.getItemAt(0)?.text?.toString())
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        assertFalse(manager.hasPrimaryClip() && manager.primaryClip?.getItemAt(0)?.text?.isNotEmpty() == true, "the copy expired")
        assertFalse(SignerClipboard.hasPending)
    }

    @Test
    fun aNewerCopyByTheUserIsNeverCleared() {
        SignerClipboard.copy(context, "0x9858EfFD232B4033E47d90003D41EC34EcaEda94")
        manager.setPrimaryClip(ClipData.newPlainText("notes", "my own text"))
        ShadowLooper.idleMainLooper(121, TimeUnit.SECONDS)
        assertEquals("my own text", manager.primaryClip?.getItemAt(0)?.text?.toString())
        assertFalse(SignerClipboard.hasPending)
    }

    @Test
    fun aSecondCopyRestartsTheTimer() {
        SignerClipboard.copy(context, "first")
        ShadowLooper.idleMainLooper(100, TimeUnit.SECONDS)
        SignerClipboard.copy(context, "second")
        ShadowLooper.idleMainLooper(100, TimeUnit.SECONDS)
        assertEquals("second", manager.primaryClip?.getItemAt(0)?.text?.toString())
        ShadowLooper.idleMainLooper(21, TimeUnit.SECONDS)
        assertFalse(manager.primaryClip?.getItemAt(0)?.text?.isNotEmpty() == true)
    }
}
