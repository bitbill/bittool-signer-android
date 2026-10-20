package com.bittool.signer.shell

import android.app.Activity
import android.content.Intent
import com.bittool.signer.harness.DebugUiTestHarness

/**
 * Debug builds: the UI-test harness reads the launch intent's `bts.uitest.*` extras **before** `super.onCreate()` of
 * `MainActivity`, so before Hilt injects anything (gaps.md G3). Without a run, nothing differs from production.
 */
internal object ShellLaunchHooks {
    fun configure(activity: Activity, intent: Intent?): ShellLaunch {
        val run = DebugUiTestHarness.configure(activity.applicationContext, intent) ?: return ShellLaunch()
        return ShellLaunch(allowCapture = run.allowCapture, uiTestRun = true)
    }
}
