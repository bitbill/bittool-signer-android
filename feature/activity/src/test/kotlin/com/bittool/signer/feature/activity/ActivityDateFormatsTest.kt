package com.bittool.signer.feature.activity

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Activity dates (activity-settings.md §2.3–§2.4, §14.2): the app locale's layout and digits, the device's clock. */
@RunWith(RobolectricTestRunner::class)
class ActivityDateFormatsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = TimeZone.getDefault()
    private val evening = ActivityTestData.utc(20, 17, 42)

    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restore() = TimeZone.setDefault(zone)

    /** The composition's context in [locale] (the app locale), as `rememberActivityDateFormats` gets it. */
    private fun formats(locale: Locale) =
        ActivityDateFormats(context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) }), locale)

    private fun clock(hours: String) = Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, hours)

    @Test
    fun theDetailDateFollowsTheDevice24HourSettingLikeTheRowTime() {
        // iOS `.formatted(date: .abbreviated, time: .standard)` honours the 24-hour setting, as the row time does.
        clock("24")
        val formats24 = formats(Locale.US)
        assertTrue("17:42:00" in formats24.dateTime(evening), formats24.dateTime(evening))
        assertTrue("17:42" in formats24.time(evening), formats24.time(evening))

        clock("12")
        val formats12 = formats(Locale.US)
        val detail = formats12.dateTime(evening)
        assertTrue("5:42:00" in detail && "PM" in detail, detail)
        assertTrue("Oct 20, 2026" in detail, detail)
        assertFalse("17:42" in formats12.time(evening), formats12.time(evening))
    }

    @Test
    fun theDayHeaderNamesTheWeekdayInTheAppLocale() {
        assertEquals("Tuesday, Oct 20, 2026", formats(Locale.US).day(LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun everyDateUsesTheDigitsOfTheLocale() {
        // Arabic as the device language writes Arabic-Indic digits in the row time; the day header and the detail date
        // must not switch to ASCII digits beside it. The app's own Arabic (ar-u-nu-latn) writes Latin digits throughout.
        clock("24")
        val deviceArabic = formats(Locale.forLanguageTag("ar"))
        val arabicDigit = Regex("[\u0660-\u0669]")
        val asciiDigit = Regex("[0-9]")
        for (text in listOf(deviceArabic.day(LocalDate.of(2026, 10, 20)), deviceArabic.dateTime(evening), deviceArabic.time(evening))) {
            assertTrue(arabicDigit.containsMatchIn(text) && !asciiDigit.containsMatchIn(text), text)
        }
        val appArabic = formats(Locale.forLanguageTag("ar-u-nu-latn"))
        for (text in listOf(appArabic.day(LocalDate.of(2026, 10, 20)), appArabic.dateTime(evening), appArabic.time(evening))) {
            assertTrue(asciiDigit.containsMatchIn(text) && !arabicDigit.containsMatchIn(text), text)
        }
    }
}
