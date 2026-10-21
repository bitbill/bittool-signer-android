package com.bittool.signer.e2e.support

import android.text.Spanned
import android.text.style.ClickableSpan
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * The iOS suite's helpers (`BitToolSignerUITests.swift:918-1154`) on top of [Ui] and [SignerApp] (gaps.md G1.0).
 * Generated phrases are read into local memory only: they are never logged, put into failure messages or captured.
 */
abstract class SignerTestCase {
    protected val ui = Ui()
    protected val app = SignerApp(ui)

    /** On a failure, the screen (redacted) goes to logcat too, next to the test's own logcat in the results. */
    @get:Rule
    val screenOnFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description?) {
            Log.e(TAG, "FAILED ${description?.methodName}: ${e?.message?.substringBefore("\n--- screen ---")}")
            ui.describeScreen().lines().chunked(40).forEach { Log.e(TAG, it.joinToString("\n")) }
        }

        override fun finished(description: Description?) {
            app.forceStop()
        }
    }

    enum class Setup { Create, Restore }

    // -----------------------------------------------------------------------------------------------------------------
    // Common targets

    protected fun walletCards(): List<UiObject2> = ui.all(SignerApp.CARD_PREFIX)
    protected val firstCard get() = ui.tagPrefix(SignerApp.CARD_PREFIX)

    /** Bottom navigation exists (iOS `app.tabBars.firstMatch.exists`). */
    protected fun tabBarExists(): Boolean = ui.exists(ui.tagPrefix("tab."))

    // -----------------------------------------------------------------------------------------------------------------
    // Helpers

    /** iOS `openSetup`: Welcome's Create or Import, then through the one-time introduction if it shows. */
    protected fun openSetup(mode: Setup) {
        val start = ui.tag(if (mode == Setup.Create) "welcome.create" else "welcome.import")
        // A just-dismissed setup may still be animating away.
        ui.waitUntil(5_000) { ui.isHittable(start) }
        ui.click(start)
        val opened = ui.waitUntil(5_000) { ui.exists(ui.tag("onboarding.next")) || !ui.exists(start) }
        if (!opened) ui.click(start)
        skipOnboardingIfShown()
    }

    /** The introduction's three pages, when it is shown (once per run). */
    protected fun skipOnboardingIfShown() {
        if (!ui.waitForExistence(ui.tag("onboarding.next"), 3_000)) return
        for (page in 1..3) {
            ui.waitFor(ui.labeled("Page $page of 3"), 5_000)
            ui.tap(ui.tag("onboarding.next"))
        }
    }

    /** iOS `assertStep(_:of:)`: the setup header "Step n of t" within 15 s. */
    protected fun assertStep(step: Int, total: Int) {
        val header = ui.startingWith("Step $step of $total")
        if (ui.waitForExistence(header, 2_000)) return
        // The header heads the step's page, which keeps its scroll position (also across backgrounding): look above.
        repeat(3) {
            if (ui.exists(header)) return
            ui.swipeDown()
        }
        ui.waitFor(header, 13_000, "Expected setup step $step of $total.")
    }

    /**
     * iOS `assertEmptySecureField`: no text, or only the placeholder (gaps.md G1.0). A filled field reports one bullet
     * per character; the value is only described by its bullet count, never shown.
     */
    protected fun assertEmptySecureField(field: Target, placeholder: String) {
        val obj = ui.waitFor(field, 5_000)
        val node = obj.accessibilityNodeInfo
        node.refresh()
        val value = node.text?.toString().orEmpty()
        assertTrue(
            "A password typed before backgrounding must be cleared (${value.length} characters reported, " +
                "${value.count { it == Ui.BULLET }} of them bullets).",
            value.isEmpty() || value == placeholder,
        )
    }

    /** iOS `revealWords`: one tap on Reveal, then the 12 words, in memory only. */
    protected fun revealWords(count: Int = 12): List<String> {
        ui.tap(ui.tag("recovery.reveal"))
        ui.waitFor(ui.tag("recovery.word.0"), 3_000)
        return (0 until count).map { index ->
            val cell = ui.tag("recovery.word.$index")
            if (!ui.scrollToExistence(cell)) ui.failWithScreen("Recovery cell $index is missing.")
            // The cell holds its position and one word. Never place the label or the word into diagnostics.
            val parts = ui.label(cell).split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }
            assertTrue("Each recovery cell must contain one English word.", parts.size == 1)
            parts.first()
        }
    }

    /** iOS `blankPositions`: the positions of the check's blanks, ascending. */
    protected fun blankPositions(): List<Int> {
        val prefix = "verification.slot."
        ui.waitFor(ui.tagPrefix(prefix), 5_000)
        val found = sortedSetOf<Int>()
        // The board scrolls on small screens: collect every blank, then come back up.
        fun collect() = ui.tagsWithPrefix(prefix).mapNotNullTo(found) { it.removePrefix(prefix).toIntOrNull() }
        collect()
        if (found.size < 4) {
            repeat(3) {
                ui.swipeUp()
                collect()
            }
            repeat(3) {
                ui.swipeDown()
                collect()
            }
        }
        return found.toList()
    }

    /** iOS `chipLabels`: the word chips in their order. */
    protected fun chipLabels(): List<String> = (0 until 8).mapNotNull { index ->
        ui.tag("verification.chip.$index").locate(ui)?.let { ui.label(it) }
    }

    private fun slotState(position: Int): String? {
        val slot = ui.tag("verification.slot.$position")
        if (!ui.scrollToExistence(slot)) ui.failWithScreen("Blank $position is missing.")
        return ui.stateDescription(slot)
    }

    /** iOS `fill`: taps the missing words in order (or with the first two differing words swapped). */
    protected fun fill(positions: List<Int>, words: List<String>, swapped: Boolean = false) {
        val order = positions.map { words[it] }.toMutableList()
        if (swapped) {
            val other = order.indices.drop(1).firstOrNull { order[it] != order[0] }
            if (other != null) order[0] = order[other].also { order[other] = order[0] }
        }
        for (word in order) {
            val chip = Target("an available chip") { u ->
                u.all("verification.chip.").firstOrNull { c ->
                    u.label(c) == word && c.accessibilityNodeInfo.stateDescription?.toString() == "Available"
                }
            }
            ui.tap(chip)
        }
    }

    /** iOS `assertBlanksEmpty`. */
    protected fun assertBlanksEmpty(positions: List<Int>) {
        for (position in positions) {
            assertTrue("Blanks start empty.", slotState(position)?.startsWith("Empty") == true)
        }
    }

    protected fun firstSlotState(position: Int): String? = slotState(position)

    /** iOS `app.alerts.firstMatch.exists`: an app window above the page (a dialog). */
    protected fun appWindowCount(): Int = ui.automation.windows.count { it.root?.packageName == SignerApp.PACKAGE }

    /** iOS `app.links.firstMatch.exists`: a tappable link in any text of the app (a `ClickableSpan` / `URLSpan`). */
    protected fun hasLinks(): Boolean {
        fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
            val text = node.text
            if (text is Spanned && text.getSpans(0, text.length, ClickableSpan::class.java).isNotEmpty()) return true
            if (depth > 40) return false
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (walk(child, depth + 1)) return true
            }
            return false
        }
        return ui.automation.windows.filter { it.root?.packageName == SignerApp.PACKAGE }.any { w -> w.root?.let { walk(it, 0) } == true }
    }

    /** Every object whose text or description matches exactly (iOS `buttons.matching(label == …).count`). */
    protected fun countLabeled(text: String): Int =
        (ui.device.findObjects(By.text(text)) + ui.device.findObjects(By.desc(text))).distinct().size

    protected fun assertExists(target: Target, message: String) {
        if (!ui.exists(target)) ui.failWithScreen(message)
    }

    protected fun assertNotExists(target: Target, message: String) {
        if (ui.exists(target)) ui.failWithScreen(message)
    }

    /**
     * iOS `XCTAssertFalse(element.exists)` on a page that scrolls: XCUITest also sees what is off screen, so the whole
     * page is searched ([Ui.anywhereOnPage]; it leaves the page where it was).
     */
    protected fun assertNowhereOnPage(target: Target, message: String) = assertNoneOnPage(target to message)

    /** [assertNowhereOnPage] for several targets in one search of the page; fails with the message of the one found. */
    protected fun assertNoneOnPage(vararg checks: Pair<Target, String>) {
        var found: String? = null
        ui.anywhereOnPage {
            found = checks.firstOrNull { (target, _) -> ui.exists(target) }?.second
            found != null
        }
        found?.let { ui.failWithScreen(it) }
    }

    /** [hasLinks] over the whole page, not only the part on screen (iOS `app.links.firstMatch.exists`). */
    protected fun hasLinksOnPage(): Boolean = ui.anywhereOnPage { hasLinks() }

    protected fun assertScrolledTo(target: Target, message: String) {
        if (!ui.scrollToExistence(target)) ui.failWithScreen(message)
    }

    /** The label of [target] contains every part, within [timeoutMs] (labels follow work done off the main thread). */
    protected fun assertLabelContains(target: Target, vararg parts: String, timeoutMs: Long = 5_000) {
        ui.waitFor(target, timeoutMs)
        if (!ui.waitUntil(timeoutMs) { ui.label(target).let { label -> parts.all { label.contains(it) } } }) {
            val label = ui.label(target)
            ui.failWithScreen("$target should contain ${parts.joinToString { "\"$it\"" }}: $label")
        }
    }

    protected fun assertDisabled(target: Target, message: String) {
        assertFalse(message, ui.find(target).isEnabled)
    }

    protected fun assertEnabled(target: Target, message: String) {
        if (!ui.waitUntil(3_000) { target.locate(ui)?.isEnabled == true }) ui.failWithScreen(message)
    }

    companion object {
        const val TAG = "SignerE2E"

        @get:ClassRule
        @JvmStatic
        val deviceSetup = DeviceSetupRule()
    }
}
