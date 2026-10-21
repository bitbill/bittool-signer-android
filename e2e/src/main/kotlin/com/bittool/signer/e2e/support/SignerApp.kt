package com.bittool.signer.e2e.support

import android.util.Log
import java.util.UUID
import org.junit.Assert.assertTrue

/**
 * The app under test, driven from outside like `XCUIApplication` (gaps.md G1.0 `launch`, `backgroundAndReactivate`):
 * cold launches through `am start` with the debug harness extras of `app/src/debug/.../harness/DebugUiTestHarness.kt`
 * (gaps.md G3), after `am force-stop`, so every launch is a new process whose first configuration wins.
 */
class SignerApp(private val ui: Ui) {
    /** The run id of the last launch (a cold launch with the same id opens the same wallets). */
    var runId: String = ""
        private set

    /**
     * iOS `launch(fixtures:dark:)`. Fixtures are created behind the Welcome screen; with [fixtures] > 0 this waits up to
     * 150 s until that many wallet cards exist and the list has settled, otherwise until Welcome's Create button exists.
     */
    fun launch(
        fixtures: Int = 0,
        dark: Boolean = false,
        reset: Boolean = true,
        appLock: Boolean = false,
        run: String = UUID.randomUUID().toString(),
        allowCapture: Boolean = false,
        locale: String? = null,
        camera: Boolean? = null,
        waitUntilReady: Boolean = true,
    ) {
        forceStop()
        camera?.let(::setCameraPermission)
        runId = run
        val extras = buildList {
            add("--es"); add(EXTRA_RUN); add(run)
            if (reset) { add("--ez"); add(EXTRA_RESET); add("true") }
            if (fixtures > 0) { add("--ei"); add(EXTRA_FIXTURES); add(fixtures.toString()) }
            if (appLock) { add("--ez"); add(EXTRA_APP_LOCK); add("true") }
            if (dark) { add("--es"); add(EXTRA_APPEARANCE); add("dark") }
            if (allowCapture) { add("--ez"); add(EXTRA_ALLOW_CAPTURE); add("true") }
            locale?.let { add("--es"); add(EXTRA_LOCALE); add(it) }
        }
        val output = Shell.run("am start -W -n $PACKAGE/$ACTIVITY ${extras.joinToString(" ")}")
        assertTrue("The app did not start: $output", output.contains("Status: ok") || output.contains("Complete"))
        if (!waitUntilReady) return
        if (fixtures > 0) {
            val fixturesLoaded = ui.waitUntil(150_000) {
                if (ui.exists(ui.containing("UI fixture setup failed"))) ui.failWithScreen("Fixture wallets could not be created.")
                ui.all(CARD_PREFIX).size >= fixtures
            }
            if (!fixturesLoaded) ui.failWithScreen("Fixture wallets must finish loading ($fixtures expected).")
            // Fixtures are created behind the Welcome screen: wait for the switch to the wallet list to finish (Welcome
            // gone, the list idle), so the first tap is not lost in the transition.
            ui.waitForNonExistence(ui.tag("welcome.create"), 10_000)
            ui.device.waitForIdle(3_000)
            assertTrue("Fixture wallets must finish loading.", ui.all(CARD_PREFIX).size == fixtures)
        } else if (!appLock) {
            ui.waitFor(ui.tag("welcome.create"), 20_000, "Welcome did not appear after a reset launch.")
        }
    }

    fun forceStop() {
        Shell.run("am force-stop $PACKAGE")
    }

    /** Grants or revokes the camera (the app process is killed by a revoke, so this runs before a launch). */
    fun setCameraPermission(granted: Boolean) {
        if (granted) {
            Shell.run("pm grant $PACKAGE android.permission.CAMERA")
        } else {
            Shell.run("pm revoke $PACKAGE android.permission.CAMERA")
            // Ask again next time (a revoke alone can leave "don't ask again" behind after earlier denials).
            Shell.run("pm clear-permission-flags $PACKAGE android.permission.CAMERA user-set user-fixed")
        }
    }

    /**
     * iOS `backgroundAndReactivate`: Home, wait until the app has really stopped (its activity reached `onStop`, where it
     * conceals and clears), then bring it back with the launcher intent and no extras (`singleTask` resumes the task, no
     * cold start). Always far below App Lock's 120 s grace.
     */
    fun backgroundAndReactivate() {
        ui.device.pressHome()
        assertTrue(
            "The app must actually enter background before testing restoration.",
            ui.waitUntil(5_000) { ui.device.currentPackageName != PACKAGE } && ui.waitUntil(10_000) { isStopped() },
        )
        Shell.run(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n $PACKAGE/$ACTIVITY",
        )
        assertTrue("The app did not come back to the foreground.", ui.waitUntil(5_000) { isForeground() })
        ui.device.waitForIdle(2_000)
    }

    fun isForeground(): Boolean = ui.device.currentPackageName == PACKAGE ||
        ui.automation.windows.any { it.root?.packageName == PACKAGE && it.isActive }

    /**
     * Whether the app's activity is stopped: its history record in `dumpsys activity activities` (the `* Hist #n:
     * ActivityRecord{… <package>/<activity> …}` block) reports `state=STOPPED`.
     */
    fun isStopped(): Boolean {
        val lines = Shell.run("dumpsys activity activities").lines()
        val start = lines.indexOfFirst { it.contains("Hist #") && it.contains("$PACKAGE/$ACTIVITY") }
        if (start < 0) return true // No activity record: the process is gone, nothing of it is in the foreground.
        val block = lines.drop(start + 1).takeWhile { !it.contains("Hist #") && !it.trimStart().startsWith("* Task") }
            .joinToString("\n")
        val state = STATE.find(block)?.groupValues?.get(1)
        if (state == null) Log.w(TAG, "No activity state in the dumpsys record of $PACKAGE")
        return state == "STOPPED" || (state == null && STOPPED_FLAG.containsMatchIn(block))
    }

    companion object {
        const val PACKAGE = "com.bittool.signer.debug"
        const val ACTIVITY = "com.bittool.signer.MainActivity"
        const val CARD_PREFIX = "wallet.card."
        private const val TAG = "SignerE2E"

        const val EXTRA_RUN = "bts.uitest.run"
        const val EXTRA_RESET = "bts.uitest.reset"
        const val EXTRA_FIXTURES = "bts.uitest.fixtures"
        const val EXTRA_APP_LOCK = "bts.uitest.appLock"
        const val EXTRA_APPEARANCE = "bts.uitest.appearance"
        const val EXTRA_LOCALE = "bts.uitest.locale"
        const val EXTRA_ALLOW_CAPTURE = "bts.uitest.allowCapture"

        private val STATE = Regex("""\bm?[sS]tate=([A-Z_]+)""")
        private val STOPPED_FLAG = Regex("""\bstopped=true""")
    }
}
