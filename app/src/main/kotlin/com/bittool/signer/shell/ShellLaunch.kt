package com.bittool.signer.shell

/**
 * How this launch was configured by the build's launch hooks (`ShellLaunchHooks`: `app/src/debug` runs the UI-test
 * harness of gaps.md G3; `app/src/release` has nothing and always returns the production defaults).
 */
data class ShellLaunch(
    /** The debug screenshot tour: `FLAG_SECURE` stays off for this process (honoured in debuggable builds only). */
    val allowCapture: Boolean = false,
    /** A debug UI-test run is active (isolated storage, fixtures, the failing fixture biometric). */
    val uiTestRun: Boolean = false,
)
