package com.bittool.signer.core.model

import java.util.Locale

/**
 * The interface language (models-state.md §9, iOS `Models/AppLanguage.swift`): [System] plus 21 bundled languages, in
 * picker order. [endonym] is never translated ([System] is shown as the localised "System").
 *
 * Android applies a language with `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))`
 * using [languageTag] (empty for [System]); the per-app locale list is the source of truth, nothing else is persisted
 * (app-shell.md §15.2). Arabic is applied as `ar-u-nu-latn` so digits stay Latin (DECISIONS.md §4).
 */
enum class AppLanguage(
    /** iOS raw value / lproj code (`system`, `en`, `zh-Hans`, …). */
    val code: String,
    val endonym: String,
    /** BCP-47 tag given to AppCompat; "" for [System]. Matches `app/src/main/res/xml/locales_config.xml`. */
    val languageTag: String,
) {
    System("system", "System", ""),
    English("en", "English", "en"),
    SimplifiedChinese("zh-Hans", "简体中文", "zh-Hans"),
    TraditionalChinese("zh-Hant", "繁體中文", "zh-Hant"),
    Japanese("ja", "日本語", "ja"),
    Korean("ko", "한국어", "ko"),
    Russian("ru", "Русский", "ru"),
    Ukrainian("uk", "Українська", "uk"),
    Spanish("es", "Español (España)", "es-ES"),
    LatinAmericanSpanish("es-419", "Español (Latinoamérica)", "es-419"),
    German("de", "Deutsch", "de"),
    French("fr", "Français", "fr"),
    Italian("it", "Italiano", "it"),
    BrazilianPortuguese("pt-BR", "Português (Brasil)", "pt-BR"),
    EuropeanPortuguese("pt-PT", "Português (Portugal)", "pt-PT"),
    Polish("pl", "Polski", "pl"),
    Indonesian("id", "Bahasa Indonesia", "id"),
    Turkish("tr", "Türkçe", "tr"),
    Thai("th", "ภาษาไทย", "th"),
    Vietnamese("vi", "Tiếng Việt", "vi"),
    Filipino("fil", "Filipino", "fil"),
    Arabic("ar", "العربية", "ar-u-nu-latn");

    /** Arabic lays out right to left (everything mirrors); [System] follows the device (null). */
    val isRightToLeft: Boolean? get() = when (this) {
        System -> null
        Arabic -> true
        else -> false
    }

    companion object {
        /** iOS `AppLanguage.current(stored)`: an unknown value is [System]. */
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: System

        /**
         * The picker entry for an applied per-app locale (null/empty → [System]): zh by script or region (Hant for
         * TW/HK/MO), es-419 for Latin-American regions, pt without a region → Brazil, legacy `in`/`tl` codes.
         */
        fun fromLocale(locale: Locale?): AppLanguage {
            if (locale == null || locale.language.isEmpty()) return System
            val language = when (locale.language) {
                "in" -> "id"
                "tl" -> "fil"
                "iw" -> "he"
                else -> locale.language
            }
            return when (language) {
                "zh" -> when {
                    locale.script == "Hant" -> TraditionalChinese
                    locale.script == "Hans" -> SimplifiedChinese
                    locale.country in setOf("TW", "HK", "MO") -> TraditionalChinese
                    else -> SimplifiedChinese
                }
                "es" -> if (locale.country.isEmpty() || locale.country == "ES") Spanish else LatinAmericanSpanish
                "pt" -> if (locale.country == "PT") EuropeanPortuguese else BrazilianPortuguese
                else -> entries.firstOrNull { it != System && it.code == language } ?: System
            }
        }
    }
}

/** Settings › Appearance › Theme (`appearance` preference). [wire] values match iOS. */
enum class Appearance(val wire: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromWire(wire: String?): Appearance = entries.firstOrNull { it.wire == wire } ?: System
    }
}

/** Current version of the introduction (`onboardingVersionCompleted >= 1` means it was completed once). */
const val ONBOARDING_VERSION = 1
