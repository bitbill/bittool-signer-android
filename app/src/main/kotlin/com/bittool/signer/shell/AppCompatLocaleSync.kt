package com.bittool.signer.shell

import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppLocalesMetadataHolderService

/**
 * Keeps a language chosen in Android's own Settings › Apps › App languages through AppCompat's one-time AndroidX →
 * framework sync (API 33+; final audit A11 follow-up).
 *
 * With `autoStoreLocales` (AndroidManifest.xml), AppCompat 1.8.0 copies its own pre-Android-13 storage into
 * `LocaleManager` once, on a background thread that the first activity's `attachBaseContext` starts, and marks it done
 * by enabling [AppLocalesMetadataHolderService]. It copies only when `AppCompatDelegate.getApplicationLocales()` is
 * empty, so that a newer framework choice wins; but on API 33+ that call reads the framework list only through a live
 * activity delegate, which `super.onCreate` registers later. On the first launch after install it therefore reads an
 * empty list and replaces a language picked in Settings (or restored onto a new phone) before the app was ever opened
 * with its own empty storage: the person's choice is gone, and plain Arabic never reaches
 * `AppLocales.ensureLatinDigits`.
 *
 * Called before that sync starts, [keepSystemChoice] reads the framework list directly and, when it holds a choice,
 * marks the sync done, exactly what AppCompat would have decided with a correct read. An empty list is left to
 * AppCompat, which then hands a choice made in the app before the device reached Android 13 to the framework.
 */
internal object AppCompatLocaleSync {
    /** Main thread, from `MainActivity.attachBaseContext` before `super.attachBaseContext` (it starts the sync). */
    fun keepSystemChoice(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val packages = context.packageManager
        val syncDone = ComponentName(context, AppLocalesMetadataHolderService::class.java)
        if (packages.getComponentEnabledSetting(syncDone) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return
        val chosen = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return
        if (chosen.isEmpty) return
        packages.setComponentEnabledSetting(
            syncDone,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
