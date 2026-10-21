package com.bittool.signer.startup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the app runs and declares at startup (QA round 1 sign-05; DECISIONS §6, offline by design), read from the
 * variant's merged manifest as the package manager sees it. EmojiCompat's startup initializer asked Google Play
 * services' downloadable-font provider for the emoji font on every launch; the app starts only the lifecycle and
 * baseline-profile initializers, declares no provider but the startup one and no broadcast receiver. The build-time
 * counterpart is `check<Variant>Components` (app/build.gradle.kts).
 */
@RunWith(RobolectricTestRunner::class)
class StartupComponentsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun startupRunsNoEmojiFontLookup() {
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, "androidx.startup.InitializationProvider"),
            PackageManager.GET_META_DATA,
        )
        assertFalse(provider.exported)
        val metadata = provider.metaData
        val initializers = metadata.keySet().filter { metadata.getString(it) == "androidx.startup" }.toSortedSet()
        assertEquals(
            sortedSetOf("androidx.lifecycle.ProcessLifecycleInitializer", "androidx.profileinstaller.ProfileInstallerInitializer"),
            initializers,
        )
        // Nothing configured EmojiCompat (its default configuration is the downloadable-font lookup).
        val emojiCompat = Class.forName("androidx.emoji2.text.EmojiCompat")
        assertFalse(emojiCompat.getMethod("isConfigured").invoke(null) as Boolean, "EmojiCompat was configured at startup")
    }

    @Test
    fun theOnlyProviderIsStartupAndThereIsNoReceiver() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS,
        )
        assertEquals(listOf("androidx.startup.InitializationProvider"), info.providers.orEmpty().map { it.name })
        assertTrue(info.receivers.isNullOrEmpty(), "receivers: ${info.receivers?.map { it.name }}")
    }
}
