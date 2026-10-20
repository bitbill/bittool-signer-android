package com.bittool.signer.feature.settings.language

import android.content.res.Resources
import com.bittool.signer.core.l10n.AppLocales
import com.bittool.signer.core.model.AppLanguage
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Inject

/**
 * The interface language (activity-settings.md §3.5, §14.5): AppCompat's per-app locale list is the source of truth
 * (`AppLocales`), so a choice made in Android 13+ "App languages" shows up here too. Applying a language recreates the
 * activity (a configuration change, not a background transition); ViewModels survive it, so screens re-read [current]
 * when they start. Main thread only.
 */
interface LanguageController {
    fun current(): AppLanguage

    fun apply(language: AppLanguage)

    /** The device's own first language, which "System" follows. */
    fun systemLocale(): Locale
}

/** [LanguageController] over `AppCompatDelegate.setApplicationLocales` (Arabic as `ar-u-nu-latn`). */
class AppCompatLanguageController @Inject constructor() : LanguageController {
    override fun current(): AppLanguage = AppLocales.current()

    override fun apply(language: AppLanguage) = AppLocales.apply(language)

    override fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    @Binds
    abstract fun languageController(controller: AppCompatLanguageController): LanguageController
}
