package com.bittool.signer.shell

import android.app.Activity
import android.content.Intent
import com.bittool.signer.harness.DebugUiTestHarness

/**
 * Debug builds: the UI-test harness reads the launch intent's `bts.uitest.*` extras **before** `super.onCreate()` of
 * `MainActivity`, so before Hilt injects anything (gaps.md G3). Below API 33 an activity attached in another language
 * than the run's is then recreated once (QA round 3 activity-keyboard-06), which only this point, before
 * `super.onCreate()`, can still see. Without a run, nothing differs from production.
 */
internal object ShellLaunchHooks {
    fun configure(activity: Activity, intent: Intent?): ShellLaunch {
        val run = DebugUiTestHarness.configure(activity.applicationContext, intent) ?: return ShellLaunch()
        DebugUiTestHarness.recreateForRunLocale(activity)
        return ShellLaunch(allowCapture = run.allowCapture, uiTestRun = true)
    }
}
