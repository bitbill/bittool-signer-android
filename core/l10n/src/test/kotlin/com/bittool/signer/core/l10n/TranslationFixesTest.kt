package com.bittool.signer.core.l10n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.model.AppLanguage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Android corrections of the iOS catalogue (`tools/l10n/overrides/`, listed for the iOS team in
 * `docs/qa/ios-translation-fixes.md`) reach the resources. QA round 1 sign-03: step 4 of Scan's "How signing works",
 * "Return" (iOS ScanTab.swift:133: return the signature to the other wallet), was translated as the keyboard's Return /
 * Enter key in 11 languages, left in English in 4 and read as "Back" in French.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationFixesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun resourcesFor(language: AppLanguage): Resources {
        val configuration = Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(language.languageTag)) }
        return context.createConfigurationContext(configuration).resources
    }

    /** The keyboard key as the iOS catalogue translated it ("Return" itself counts outside English). */
    private val keyboardKeys = setOf("Return", "Enter", "Intro", "Invio", "Ввод", "改行", "줄바꿈", "Entrée", "Retour")

    @Test
    fun theReturnStepMeansSendingTheSignatureBack() {
        assertEquals("Return", context.resources.getString(R.string.return_text))
        for (language in AppLanguage.entries.filter { it != AppLanguage.System && it != AppLanguage.English }) {
            val resources = resourcesFor(language)
            val step = resources.getString(R.string.return_text)
            assertFalse(step in keyboardKeys, "${language.languageTag}: step 4 is \"$step\", the keyboard key")
            assertNotEquals(resources.getString(R.string.back), step, "${language.languageTag}: step 4 reads as Back")
        }
        assertEquals("返送", resourcesFor(AppLanguage.Japanese).getString(R.string.return_text))
        // Languages whose iOS translation was right keep it.
        assertEquals("Zurückgeben", resourcesFor(AppLanguage.German).getString(R.string.return_text))
    }
}
