package com.bittool.signer.core.l10n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.text.TextUtils
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.model.AppLanguage
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Port of iOS `AppNameTests` (BitToolSignerTests/AppNameTests.swift, 3 cases), plus the Android language-resolution
 * check of activity-settings.md §14.5 pitfall (a). "The app is called BitTool Signer in every language; only the
 * interface is translated. Each bundled localisation must actually carry strings, so the picker never offers a
 * language that would leave the interface in English."
 */
@RunWith(RobolectricTestRunner::class)
class AppNameTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Every interface language except System and English. */
    private val translated = AppLanguage.entries.filter { it != AppLanguage.System && it != AppLanguage.English }

    private fun resourcesFor(locales: LocaleList): android.content.res.Resources {
        val configuration = Configuration(context.resources.configuration).apply { setLocales(locales) }
        return context.createConfigurationContext(configuration).resources
    }

    private fun resourcesFor(language: AppLanguage) = resourcesFor(LocaleList.forLanguageTags(language.languageTag))

    /** iOS `theAppIsNamedBitToolSignerEverywhere`: no localisation renames the app. */
    @Test
    fun theAppIsNamedBitToolSignerEverywhere() {
        assertEquals("BitTool Signer", context.resources.getString(R.string.bittool_signer))
        for (language in AppLanguage.entries.filter { it != AppLanguage.System }) {
            assertEquals("BitTool Signer", resourcesFor(language).getString(R.string.bittool_signer), language.languageTag)
        }
    }

    /**
     * iOS `everyTranslatedLanguageTranslatesTheSystemPrompts`: Android shows the system's own camera dialog, so the one
     * system-prompt text the app owns is the camera rationale; every translated language translates it.
     */
    @Test
    fun everyTranslatedLanguageTranslatesTheSystemPrompts() {
        val english = context.resources.getString(R.string.camera_usage_description)
        for (language in translated) {
            assertNotEquals(english, resourcesFor(language).getString(R.string.camera_usage_description), language.languageTag)
        }
    }

    /** iOS `everyOfferedLanguageIsTranslated`: more than 200 strings each, "Settings" translated, Arabic right-to-left. */
    @Test
    fun everyOfferedLanguageIsTranslated() {
        val resDirectory = File("src/main/res")
        for (language in translated) {
            val resources = resourcesFor(language)
            val settings = resources.getString(R.string.settings)
            assertNotEquals("Settings", settings, "${language.languageTag} must translate Settings")
            val folder = valuesFolder(resDirectory, language)
            val count = Regex("<string\\b").findAll(File(folder, "strings_ios.xml").readText()).count()
            assertTrue(count > 200, "${language.languageTag} has only $count strings")
        }
        assertEquals(View.LAYOUT_DIRECTION_RTL, TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(AppLanguage.Arabic.languageTag)))
        assertEquals(View.LAYOUT_DIRECTION_LTR, TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(AppLanguage.English.languageTag)))
    }

    /**
     * activity-settings.md §14.5 pitfall (a): "System" with the device languages `[en-GB, de]` must show English, not
     * the first listed language that has its own folder (German). The generated `values-en/` copy
     * (core/l10n/build.gradle.kts) makes every English locale a real match.
     */
    @Test
    fun anEnglishDeviceListedBeforeGermanResolvesToEnglish() {
        val resources = resourcesFor(LocaleList.forLanguageTags("en-GB,de-DE"))
        assertEquals("Settings", resources.getString(R.string.settings))
        assertEquals("Unable to Continue", resources.getString(R.string.unable_to_continue))
        // The Android-only strings are covered by the same copy.
        assertEquals("App Lock", resources.getString(R.string.security_app_lock))
        // German alone still resolves to German.
        assertEquals("Einstellungen", resourcesFor(LocaleList.forLanguageTags("de-DE")).getString(R.string.settings))
    }

    /**
     * The explicit English locale is generated, not committed: every translatable string of `values/strings_*.xml`
     * (iOS catalogue and Android-only files alike) is in `values-en/`, so a later `strings_<area>.xml` is covered too.
     * (The framework of API 24+ already prefers English for an English-first list; the copy keeps that true wherever
     * English is not the first-listed match, e.g. through AppCompat's per-app locales before API 33.)
     */
    @Test
    fun theEnglishLocaleCopiesEveryTranslatableString() {
        val source = File("src/main/res/values")
        val generated = File("build/generated/res/englishLocale/values-en")
        val sources = source.listFiles { file -> file.name.startsWith("strings_") && file.name.endsWith(".xml") }.orEmpty()
        assertTrue(sources.size >= 2, "no strings files found in $source")
        val stringName = Regex("""<string\s+name="([^"]+)"([^>]*)>""")
        for (file in sources) {
            val translatable = stringName.findAll(file.readText())
                .filterNot { it.groupValues[2].contains("translatable=\"false\"") }
                .map { it.groupValues[1] }.toSet()
            val copied = File(generated, file.name).takeIf { it.isFile }?.readText()?.let { text ->
                stringName.findAll(text).map { it.groupValues[1] }.toSet()
            }.orEmpty()
            assertEquals(translatable, copied, "values-en/${file.name}")
        }
    }

    /** The resource folder of [language] among the generated ones (`values-de`, `values-b+zh+Hans`, …). */
    private fun valuesFolder(res: File, language: AppLanguage): File {
        val locale = Locale.forLanguageTag(language.languageTag)
        val candidates = listOf(
            "values-b+" + listOfNotNull(locale.language, locale.script.takeIf { it.isNotEmpty() }, locale.country.takeIf { it.isNotEmpty() }).joinToString("+"),
            "values-b+" + listOfNotNull(locale.language, locale.script.takeIf { it.isNotEmpty() }).joinToString("+"),
            "values-${locale.language}-r${locale.country}",
            "values-${locale.language}",
            "values-b+${locale.language}",
            if (locale.language == "id") "values-in" else "",
            if (locale.language == "fil") "values-tl" else "",
        ).filter { it.isNotEmpty() }
        return candidates.map { File(res, it) }.firstOrNull { File(it, "strings_ios.xml").isFile }
            ?: error("No resource folder for ${language.languageTag} (tried $candidates)")
    }
}
