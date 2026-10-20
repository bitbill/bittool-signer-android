package com.bittool.signer.shell

import android.app.Activity
import android.content.Intent

/**
 * Release builds have no launch hooks: no UI-test harness exists in them (gaps.md G3; `checkApk/PlayReleaseHasNoHarness`
 * verifies the R8 output), and launch-intent extras are never read.
 */
internal object ShellLaunchHooks {
    @Suppress("UNUSED_PARAMETER")
    fun configure(activity: Activity, intent: Intent?): ShellLaunch = ShellLaunch()
}
