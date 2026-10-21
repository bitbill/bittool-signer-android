package com.bittool.signer.harness

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.AppLocalesStorageHelper
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.shell.ShellLaunchHooks
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * QA round 3 activity-keyboard-06 on API 31: `ui.py launch --locale ar`, then a launch in English, opened in Arabic or
 * turned Arabic at the first rotation. AppCompat (`autoStoreLocales`) attaches the activity in the language it stored
 * before the harness can read the launch extras; the harness's language reached that activity only in place, so the
 * first configuration change the activity handles itself went back to the attached one, and the record kept the old
 * language for the next launch. Now the harness recreates that activity once, so it is attached in the run's language,
 * which AppCompat then stores.
 *
 * Robolectric never lets AppCompat wrap an activity's base context (its `Build.FINGERPRINT` is "robolectric"), so the
 * language an activity is attached in is the device language here (qualifiers): the same state as on a device, which a
 * device language other than the run's also reaches. AppCompat's record is read and written as on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class HarnessRunLocaleTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val runId = "3D9A7C21-5B4E-4F60-9A1B-2C3D4E5F6A7B"
    private val launched = mutableListOf<ActivityController<HostActivity>>()

    /** `MainActivity`'s first steps: the launch hooks before `super.onCreate`. */
    class HostActivity : AppCompatActivity() {
        /** The primary language this instance was attached with (before `super.onCreate` updates it in place). */
        lateinit var attached: Locale

        override fun onCreate(savedInstanceState: Bundle?) {
            attached = resources.configuration.locales[0]
            created += this
            ShellLaunchHooks.configure(this, intent)
            setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
            super.onCreate(savedInstanceState)
        }

        companion object {
            /** Every instance created in this test, in order (Robolectric's `setup()` already runs a recreation). */
            val created = mutableListOf<HostActivity>()
        }
    }

    @Before
    fun setUp() {
        newProcess()
        storeLanguage("")
        HostActivity.created.clear()
    }

    @After
    fun tearDown() {
        // AppCompat keeps its live delegates process-wide: a later test's language change must not reach these.
        launched.forEach { runCatching { it.pause().stop().destroy() } }
        HostActivity.created.clear()
        newProcess()
        storeLanguage("")
        DebugUiTestHarness.resetForTest()
    }

    /** What a cold launch starts from: no harness configuration, and AppCompat has not read its record yet. */
    private fun newProcess() {
        DebugUiTestHarness.resetForTest()
        DebugUiTestHarness.isEmulator = { true }
        DebugUiTestHarness.createsFixtures = false
        AppCompatDelegate::class.java.getDeclaredMethod("resetStaticRequestedAndStoredLocales")
            .apply { isAccessible = true }
            .invoke(null)
    }

    /** AppCompat's own record of the per-app language below API 33 (what `autoStoreLocales` reads at attach). */
    @SuppressLint("RestrictedApi")
    private fun storeLanguage(tags: String) = AppLocalesStorageHelper.persistLocales(context, tags)

    @SuppressLint("RestrictedApi")
    private fun storedLanguage(): String = AppLocalesStorageHelper.readLocales(context)

    /**
     * A cold launch of the run in [locale] (the harness default without one) whose activity is attached in [attachedIn]
     * (Robolectric qualifiers, set here: AppCompat's in-place updates in an earlier test can outlive it), with the main
     * looper idle afterwards.
     */
    private fun launch(locale: String?, attachedIn: String): ActivityController<HostActivity> {
        RuntimeEnvironment.setQualifiers(attachedIn)
        val intent = Intent(context, HostActivity::class.java).putExtra(DebugUiTestHarness.EXTRA_RUN, runId)
        locale?.let { intent.putExtra(DebugUiTestHarness.EXTRA_LOCALE, it) }
        return Robolectric.buildActivity(HostActivity::class.java, intent).setup().also(launched::add).also { idle() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun theRunsLanguageWinsOverTheOneTheActivityWasAttachedIn() {
        storeLanguage("ar-u-nu-latn") // `launch --locale ar`, with MainActivity's Latin-digit re-apply
        val controller = launch("en-US", attachedIn = "ar")
        val created = HostActivity.created.toList()
        assertEquals(2, created.size, "recreated once")
        assertEquals("ar", created[0].attached.language, "attached in Arabic, before the harness could read the extras")
        assertSame(created[1], controller.get())
        assertEquals("en-US", controller.get().resources.configuration.locales[0].toLanguageTag())
        assertEquals("en-US", storedLanguage(), "stored, so the activity is attached in it from now on")
        idle()
        assertEquals(2, HostActivity.created.size, "only once")
    }

    @Test
    fun theDefaultLanguageWinsToo() {
        // `launch --keep` without --locale after an Arabic run.
        storeLanguage("ar-u-nu-latn")
        launch(locale = null, attachedIn = "ar")
        assertEquals(2, HostActivity.created.size)
        assertEquals("en-US", storedLanguage())
    }

    @Test
    fun aLaunchInTheLanguageTheActivityWasAttachedInIsNotRecreated() {
        storeLanguage("de-DE")
        launch("de-DE", attachedIn = "de-rDE")
        assertEquals(1, HostActivity.created.size)
        assertEquals("de-DE", storedLanguage())
    }

    @Test
    fun arabicIsTheRunsArabic() {
        storeLanguage("ar-u-nu-latn")
        launch("ar", attachedIn = "ar")
        assertEquals(1, HostActivity.created.size, "the same language: no recreation")
    }

    @Test
    fun onlyTheActivityOfTheRunsLaunchIsChecked() {
        storeLanguage("en-US")
        val controller = launch("en-US", attachedIn = "en-rUS")
        assertEquals(1, HostActivity.created.size)
        // The Language page later picks German: AppCompat recreates the activity itself, the harness leaves it alone.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("de-DE"))
        idle()
        assertEquals(2, HostActivity.created.size)
        assertSame(HostActivity.created[1], controller.get())
        idle()
        assertEquals(2, HostActivity.created.size)
    }
}
