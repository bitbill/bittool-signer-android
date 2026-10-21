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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
 * AppCompat keeps the list itself (plain `ar` there comes from the UI-test harness or older storage); the re-apply runs
 * before `super.onCreate`, so the first activity already shows it instead of being recreated.
 * Only plain Arabic is touched: another language, or System (no per-app language), stays as the person chose.
 *
 * AppCompat's one-time AndroidX → framework sync (API 33+) runs on its own thread when the first activity attaches, and
 * reads the framework list through that same delegate: on the first launch it read an empty list and replaced a language
 * picked in Settings before the app was ever opened with AppCompat's own (empty) storage. The first-launch tests make it
 * finish before `onCreate`, the order in which it erased the choice; the others start after it ran once, as on any
 * device that has opened the app before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = SignerApplication::class)
class ArabicLatinDigitsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var activity: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        resetAppCompatLocales()
    }

    @After
    fun tearDown() {
        awaitAppCompatLocaleStorage()
        activity?.destroy()
        resetAppCompatLocales()
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.BAKLAVA])
    fun plainArabicFromAndroidSettingsGetsLatinDigits() {
        markFrameworkSyncDone()
        setFrameworkLocales("ar")
        launch()
        assertEquals("ar-u-nu-latn", frameworkLocales())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.BAKLAVA])
    fun anotherLanguageFromAndroidSettingsStaysAsChosen() {
        markFrameworkSyncDone()
        setFrameworkLocales("de")
        launch()
        assertEquals("de", frameworkLocales())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.BAKLAVA])
    fun systemLanguageStaysSystem() {
        markFrameworkSyncDone()
        launch()
        assertEquals("", frameworkLocales())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.BAKLAVA])
    fun arabicChosenInAndroidSettingsBeforeTheFirstLaunchSurvivesIt() {
        setFrameworkLocales("ar")
        launchAfterAppCompatsFrameworkSync()
        assertEquals("ar-u-nu-latn", frameworkLocales())
        assertTrue(frameworkSyncDone(), "AppCompat's one-time sync is marked done")
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.BAKLAVA])
    fun anotherLanguageChosenInAndroidSettingsBeforeTheFirstLaunchSurvivesIt() {
        setFrameworkLocales("de")
        launchAfterAppCompatsFrameworkSync()
        assertEquals("de", frameworkLocales())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.BAKLAVA])
    fun aLanguageChosenInTheAppBeforeAndroid13StillReachesAndroid() {
        // Chosen in the app on Android 12 (AppCompat's own storage); the device then updated to Android 13+.
        persistAppCompatLocales("de")
        launchAfterAppCompatsFrameworkSync()
        assertEquals("de", frameworkLocales())
        assertTrue(frameworkSyncDone(), "AppCompat's one-time sync is marked done")
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O, Build.VERSION_CODES.S_V2])
    fun plainArabicKeptByAppCompatBelowApi33GetsLatinDigitsInTheFirstActivity() {
        // No activity exists yet: AppCompat only records the list, as its storage would hand it to the next start.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))
        launch()
        assertEquals("ar-u-nu-latn", AppCompatDelegate.getApplicationLocales().toLanguageTags())
        // Applied to the activity being created, not through a recreation.
        assertEquals("ar-u-nu-latn", activity!!.get().resources.configuration.locales[0].toLanguageTag())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O, Build.VERSION_CODES.S_V2])
    fun anotherLanguageKeptByAppCompatBelowApi33StaysAsChosen() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("de"))
        launch()
        assertEquals("de", AppCompatDelegate.getApplicationLocales().toLanguageTags())
        assertEquals("de", activity!!.get().resources.configuration.locales[0].toLanguageTag())
    }

    private fun launch() {
        activity = Robolectric.buildActivity(MainActivity::class.java).create()
    }

    /**
     * The first launch, in the order that erased a Settings choice: building the controller attaches the activity, which
     * starts AppCompat's sync on its thread; it finishes before `onCreate` registers the AppCompat delegate.
     */
    private fun launchAfterAppCompatsFrameworkSync() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        activity = controller
        awaitAppCompatLocaleStorage()
        controller.create()
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

    /** AppCompat marks its one-time sync done by enabling this component (it has run on any device after one launch). */
    private val syncMarker: ComponentName get() = ComponentName(context, AppLocalesMetadataHolderService::class.java)

    private fun markFrameworkSyncDone() = context.packageManager.setComponentEnabledSetting(
        syncMarker,
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )

    private fun frameworkSyncDone(): Boolean =
        context.packageManager.getComponentEnabledSetting(syncMarker) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** AppCompat's own storage (`autoStoreLocales`), as it persists the in-app choice below API 33. */
    private fun persistAppCompatLocales(tags: String) {
        Class.forName("androidx.core.app.AppLocalesStorageHelper")
            .getMethod("persistLocales", Context::class.java, String::class.java)
            .invoke(null, context, tags)
    }

    /** Waits until AppCompat's locale storage thread (its serial executor) has run everything queued so far. */
    private fun awaitAppCompatLocaleStorage() {
        val storage = AppCompatDelegate::class.java.getDeclaredField("sSerialExecutorForLocalesStorage")
            .apply { isAccessible = true }
            .get(null) as Executor
        val drained = CountDownLatch(1)
        storage.execute { drained.countDown() }
        assertTrue(drained.await(10, TimeUnit.SECONDS), "AppCompat's locale storage thread did not finish")
    }

    /**
     * AppCompat's process-wide locale state outlives a test in the same sandbox: start each one as a cold process (the
     * lists kept below API 33, and whether the one-time framework sync was checked).
     */
    private fun resetAppCompatLocales() {
        AppCompatDelegate::class.java.getDeclaredMethod("resetStaticRequestedAndStoredLocales")
            .apply { isAccessible = true }
            .invoke(null)
        AppCompatDelegate::class.java.getDeclaredField("sIsFrameworkSyncChecked")
            .apply { isAccessible = true }
            .setBoolean(null, false)
    }
}
