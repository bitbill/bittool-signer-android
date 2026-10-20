package com.bittool.signer.core.ui.password

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bittool.signer.core.designsystem.theme.SignerTheme
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element

/**
 * Backlog B1 on a device, exactly the way the QA tours drive the app (`tools/qa/ui.py`): `uiautomator dump` to find a
 * node by its resource id (the test tag), `input tap` on its bounds, `input text` through the keyboard. UiAutomator
 * still finds the password field and typing still works, but the dump never holds the password: the field's text
 * attribute is one bullet per character. (On API 34+ UiAutomation connects as an accessibility tool, so the field's
 * accessibility-data-sensitive marking does not hide it from the dump; that services which are not tools get no node at
 * all is covered by the Robolectric `PasswordFieldTest`.)
 *
 * It injects a tap and key events: run it on a device nobody else is using. Before each injection it checks that the
 * test's own window has the focus and stops otherwise, so it never types into another app.
 */
@RunWith(AndroidJUnit4::class)
class PasswordFieldDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Shell access only: no accessibility connection of its own, so `uiautomator dump` can register its own. */
    private val shell: UiAutomation by lazy {
        InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
    }

    @Test
    fun uiAutomatorTypesThroughTheKeyboardButNeverReadsThePassword() {
        val state = TextFieldState()
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    PasswordField(PasswordKind.WalletPassword, state, Modifier.padding(24.dp))
                }
            }
        }
        compose.waitForIdle()

        val field = dumpedField { true }
        assertNotNull("uiautomator dump lists the field under its tag (last dump: $lastDumpOutput)", field)
        val (x, y) = center(field!!.getAttribute("bounds"))
        requireOwnWindowFocused()
        run("input tap $x $y")
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag(FIELD_ID) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        requireOwnWindowFocused()
        run("input text $TYPED")
        compose.waitUntil(TIMEOUT) { state.text.toString() == TYPED }

        // Compose publishes the edit to accessibility shortly after it: dump until the node has every character.
        val typed = dumpedField { it.getAttribute("text").length == TYPED.length }
        assertNotNull("the field is still listed once filled", typed)
        val text = typed!!.getAttribute("text")
        // Never printed: on a mismatch the message shows the text's shape only (letters and digits become 'x').
        val shape = text.map { if (it.isLetterOrDigit()) 'x' else it }.joinToString("")
        assertTrue("the dump has ${TYPED.length} characters, not \"$shape\"", text.length == TYPED.length)
        assertFalse("the typed password is not in the dump", text.contains(TYPED))
        assertTrue("one bullet per typed character, not \"$shape\"", text == "•".repeat(TYPED.length))
        assertTrue("password semantics", typed.getAttribute("password") == "true")
    }

    /**
     * The field's `<node>` from `uiautomator dump` once [accept] holds, retrying for a while: a dump from a QA tour on
     * the same emulator holds the single UiAutomation slot for a moment, and Compose publishes edits with a delay.
     */
    private fun dumpedField(accept: (Element) -> Boolean): Element? {
        val deadline = SystemClock.uptimeMillis() + 60_000
        var last: Element? = null
        while (SystemClock.uptimeMillis() < deadline) {
            lastDumpOutput = run("uiautomator dump $DUMP").trim()
            val xml = run("cat $DUMP")
            run("rm -f $DUMP")
            if (xml.contains("<hierarchy")) {
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(xml.byteInputStream()).getElementsByTagName("node")
                val elements = (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("package") == ownPackage }
                lastDumpOutput += " | packages ${elements.map { it.getAttribute("package") }.toSet()}" +
                    " | ids ${elements.map { it.getAttribute("resource-id") }.filter { it.isNotEmpty() }.take(12)}"
                last = elements.firstOrNull { it.getAttribute("resource-id") == FIELD_ID }
                if (last != null && accept(last)) return last
            } else {
                lastDumpOutput += " | no hierarchy (${xml.take(80)})"
            }
            SystemClock.sleep(500)
        }
        return last
    }

    /** What the last `uiautomator dump` printed ("UI hierchary dumped to: …" or its error), for failure messages. */
    private var lastDumpOutput = ""

    private val ownPackage: String get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName

    /** Stops the test unless the focused window is this test's activity (another app may have come to the front). */
    private fun requireOwnWindowFocused() {
        val focus = run("dumpsys window").lineSequence().firstOrNull { "mCurrentFocus" in it }.orEmpty()
        check(ownPackage in focus) { "another window has the focus ($focus): not injecting input" }
    }

    private fun center(bounds: String): Pair<Int, Int> {
        val (left, top, right, bottom) = Regex("\\d+").findAll(bounds).map { it.value.toInt() }.toList()
        return (left + right) / 2 to (top + bottom) / 2
    }

    private fun run(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(shell.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    private companion object {
        const val FIELD_ID = "Wallet password"
        const val DUMP = "/data/local/tmp/bts-password-field.xml"

        /** A throwaway test value (letters and digits only, so `input text` needs no escaping). */
        const val TYPED = "QaTyped2468"
        const val TIMEOUT = 10_000L
    }
}
