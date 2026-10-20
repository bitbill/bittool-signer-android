package com.bittool.signer.core.l10n

import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.bittool.signer.core.model.AppLanguage
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.fallbackEnglish
import com.bittool.signer.core.model.review.EngineTextLookup
import com.bittool.signer.core.model.review.ReviewText

/**
 * Resolves a [UiText] with [resources] of the current per-app locale (an activity's or a composition's resources —
 * on API < 33 the Application context keeps the system locale). iOS catalogue strings are found by their English source
 * text; a key that is not in the catalogue shows its English with the arguments substituted (iOS behaviour).
 */
fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Ios -> IosStrings.lookup(english)?.let { id -> resources.format(id, args) } ?: fallbackEnglish()
    is UiText.Resource -> resources.format(id, args)
    is UiText.Verbatim -> text
}

private fun Resources.format(id: Int, args: List<Any>): String =
    if (args.isEmpty()) getString(id) else getString(id, *args.toTypedArray())

/**
 * The draw-time translation of engine English (models-state.md §4, §12.4): the catalogue string for an exact engine
 * phrase in [resources]' language, or null. Never routes engine text through `getIdentifier`.
 */
class ResourcesEngineTextLookup(private val resources: Resources) : EngineTextLookup {
    override fun translation(english: String): String? = IosStrings.lookup(english)?.let(resources::getString)
}

/** A [ReviewText] for [resources]' language (create one per configuration; it is cheap). */
fun Resources.reviewText(): ReviewText = ReviewText(ResourcesEngineTextLookup(this))

/**
 * Per-app language (app-shell.md §15.2, ownbit-build-release.md §8.7.4): AppCompat's application locale list is the
 * source of truth (persisted by the framework on API 33+, by `AppLocalesMetadataHolderService` below). Main thread
 * only; applying recreates the activity (not a background transition: `isChangingConfigurations`).
 */
object AppLocales {
    /** The picker's current entry (empty list → System). */
    fun current(): AppLanguage {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) AppLanguage.System else AppLanguage.fromLocale(locales[0])
    }

    /** Applies [language]; System clears the per-app list (follow the device). Arabic uses Latin digits. */
    fun apply(language: AppLanguage) {
        val list = if (language == AppLanguage.System) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language.languageTag)
        AppCompatDelegate.setApplicationLocales(list)
    }

    /**
     * Re-applies `ar-u-nu-latn` when Android's own "App languages" setting chose plain Arabic, so digits stay Latin.
     * Call from `MainActivity.onCreate`.
     */
    fun ensureLatinDigits() {
        val first = AppCompatDelegate.getApplicationLocales()[0] ?: return
        if (first.language == "ar" && first.getUnicodeLocaleType("nu") != "latn") apply(AppLanguage.Arabic)
    }
}
