package com.bittool.signer

import android.app.Application
import com.bittool.signer.appearance.AppearanceController
import com.bittool.signer.core.common.coroutines.ApplicationScope
import com.bittool.signer.core.common.coroutines.runCatchingCancellable
import com.bittool.signer.core.common.log.Log
import com.bittool.signer.core.engine.SignerEngine
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The process (app-shell.md §2.1; ownbit-build-release.md §9.3). In order, before any activity exists:
 * 1. the debug log sink (release builds log nothing: `Log.enabled` stays false);
 * 2. the last applied Theme as the AppCompat night mode, so the first activity already starts in the in-app
 *    appearance ([AppearanceController.applyCached]);
 * 3. the signing engine's `prewarm()` (bundle verification and bytecode compilation, ≈ 0.3 s on its own thread), so the
 *    first engine call after launch does not pay for it.
 *
 * Nothing here opens the signer's storage (vaults, public data, Activity, preferences): in a debug UI-test run the
 * harness chooses that storage in `MainActivity.onCreate`, after this (gaps.md G3). No network, analytics or
 * background work exists to start.
 */
@HiltAndroidApp
class SignerApplication : Application() {
    @Inject lateinit var engine: Lazy<SignerEngine>
    @Inject lateinit var appearance: AppearanceController
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Log.sink = Log.AndroidSink
            Log.enabled = true
        }
        appearance.applyCached()
        scope.launch {
            runCatchingCancellable { engine.get().prewarm() }
                .onFailure { Log.w(TAG, it) { "Engine prewarm failed; the first engine call will report it" } }
        }
    }

    private companion object {
        const val TAG = "SignerApplication"
    }
}
