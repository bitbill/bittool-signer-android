package com.bittool.signer.startup

import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppLocalesMetadataHolderService
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.MainActivity
import com.bittool.signer.SignerApplication
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Arabic keeps Latin digits however it was chosen (DECISIONS §4; ownbit-build-release.md §8.7.4–§8.7.5; final audit
 * A11). Android 13+'s own Settings › Apps › App languages offers plain `ar` (locales_config.xml), which would format
 * every counter, percentage, date and byte count with Arabic-Indic digits; the real `MainActivity` re-applies it as
 * `ar-u-nu-latn` on every start. On API 33+ AppCompat reaches the framework `LocaleManager` only through a live activity
 * delegate, which `super.onCreate` registers, so a re-apply before it read an empty list and did nothing. Below API 33
 * AppCompat keeps the list itself (plain `ar` there can only come from older storage); the re-apply must keep working.
 * Only plain Arabic is touched: another language, or System (no per-app language), stays as the person chose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = SignerApplication::class)
class ArabicLatinDigitsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var activity: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        resetAppCompatLocales()
        // AppCompat's one-time AndroidX → framework sync (API 33+) has already run on any device that launched the app
        // once; on the very first launch it races the activity and can replace the framework list with AppCompat's own
        // (empty) storage. Mark it done, as AppCompat itself does, so the test sees the steady state.
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, AppLocalesMetadataHolderService::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    @After
    fun tearDown() {
        activity?.destroy()
        resetAppCompatLocales()
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.BAKLAVA])
    fun plainArabicFromAndroidSettingsGetsLatinDigits() {
        setFrameworkLocales("ar")
        launch()
        assertEquals("ar-u-nu-latn", frameworkLocales())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.BAKLAVA])
    fun anotherLanguageFromAndroidSettingsStaysAsChosen() {
        setFrameworkLocales("de")
        launch()
        assertEquals("de", frameworkLocales())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.BAKLAVA])
    fun systemLanguageStaysSystem() {
        launch()
        assertEquals("", frameworkLocales())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O, Build.VERSION_CODES.S_V2])
    fun plainArabicKeptByAppCompatBelowApi33GetsLatinDigits() {
        // No activity exists yet: AppCompat only records the list, as its storage would hand it to the next start.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))
        launch()
        assertEquals("ar-u-nu-latn", AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O, Build.VERSION_CODES.S_V2])
    fun anotherLanguageKeptByAppCompatBelowApi33StaysAsChosen() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("de"))
        launch()
        assertEquals("de", AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }

    private fun launch() {
        activity = Robolectric.buildActivity(MainActivity::class.java).create()
    }

    /** Android's own per-app list (API 33+), the one its App languages setting writes. */
    private fun setFrameworkLocales(tags: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tags)
        } else {
            error("Android keeps per-app languages itself from API 33")
        }
    }

    private fun frameworkLocales(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        } else {
            error("Android keeps per-app languages itself from API 33")
        }

    /** AppCompat's process-wide locale lists (below API 33) outlive a test in the same sandbox: start each one cold. */
    private fun resetAppCompatLocales() {
        AppCompatDelegate::class.java.getDeclaredMethod("resetStaticRequestedAndStoredLocales")
            .apply { isAccessible = true }
            .invoke(null)
    }
}
