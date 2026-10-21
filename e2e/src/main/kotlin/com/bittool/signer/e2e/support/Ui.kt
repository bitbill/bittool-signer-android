package com.bittool.signer.e2e.support

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.util.regex.Pattern
import org.junit.Assert.fail

/**
 * Something on screen the suite looks for: a lazily evaluated query (an iOS `XCUIElement`, which is also a query). It
 * is re-evaluated on every use, so it never goes stale.
 */
class Target(private val name: String, private val locator: (Ui) -> UiObject2?) {
    fun locate(ui: Ui): UiObject2? = try {
        locator(ui)
    } catch (e: StaleObjectException) {
        null
    }

    override fun toString(): String = name
}

/**
 * The UiAutomator side of the iOS helpers (gaps.md G1.0 "Helpers"): queries over test tags (exposed as resource ids in
 * debug builds), texts and content descriptions of every window (dialogs and sheets are windows of their own), waits on
 * conditions, scrolling, taps, typing and the state reads (selected, checked, state description, heading) that the iOS
 * suite makes through accessibility values.
 *
 * Scrolling and taps work inside the *visible band* of a page: content scrolls under the top app bar, pinned bottom
 * bars and the tab bar, where accessibility still reports it on screen, so a node only counts as hittable when its
 * centre lies between those bars, and drags never start on a bar (which owns its touches).
 */
class Ui {
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    init {
        // Every query first waits for the UI to be idle; animated QR codes (a counter that changes every part) are never
        // idle, so a long idle timeout would make every query on those pages slow. Waits are condition-based anyway.
        Configurator.getInstance().waitForIdleTimeout = IDLE_TIMEOUT_MS
        Configurator.getInstance().actionAcknowledgmentTimeout = 1_500
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }

    val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    // -----------------------------------------------------------------------------------------------------------------
    // Queries

    /** The object tagged [tag] (`Modifier.testTag`, a resource id in debug builds). */
    fun tag(tag: String) = Target("tag '$tag'") { it.device.findObject(By.res(tag)) }

    /** The first object whose tag starts with [prefix] (iOS `identifier BEGINSWITH`). */
    fun tagPrefix(prefix: String) = Target("tag prefix '$prefix'") { it.device.findObject(By.res(prefixPattern(prefix))) }

    /** Every object whose tag starts with [prefix]. */
    fun all(prefix: String): List<UiObject2> = device.findObjects(By.res(prefixPattern(prefix)))

    /** The tags starting with [prefix] that are on screen now. */
    fun tagsWithPrefix(prefix: String): List<String> = all(prefix).mapNotNull { obj ->
        try {
            obj.resourceName
        } catch (e: StaleObjectException) {
            null
        }
    }

    /** iOS `element(containing:)`: the first object whose text **or** content description contains [text], any case. */
    fun containing(text: String) = Target("text containing '$text'") { ui ->
        ui.device.findObject(By.text(containsPattern(text))) ?: ui.device.findObject(By.desc(containsPattern(text)))
    }

    /** iOS `element(labeled:)` / `buttons["Label"]`: the first object whose text or content description is [text]. */
    fun labeled(text: String) = Target("label '$text'") { ui ->
        ui.device.findObject(By.text(text)) ?: ui.device.findObject(By.desc(text))
    }

    /** iOS `label BEGINSWITH`: text or content description starting with [text]. */
    fun startingWith(text: String) = Target("label starting with '$text'") { ui ->
        val pattern = Pattern.compile(Pattern.quote(text) + ".*", Pattern.DOTALL)
        ui.device.findObject(By.text(pattern)) ?: ui.device.findObject(By.desc(pattern))
    }

    /** iOS `navigationBars["Title"]`: a heading (the top bar's title, a sheet's title) reading exactly [text]. */
    fun title(text: String) = Target("title '$text'") { ui ->
        ui.device.findObjects(By.text(text)).firstOrNull { it.isHeading() }
    }

    /** A dialog or alert title (iOS `alerts["Title"]`): a heading, or failing that the exact text. */
    fun dialog(title: String) = Target("dialog '$title'") { ui ->
        ui.device.findObjects(By.text(title)).firstOrNull { it.isHeading() } ?: ui.device.findObject(By.text(title))
    }

    /** The bottom navigation's tab (iOS `tabBars.buttons["Scan"]`): `tab.<name>`. */
    fun tab(name: String) = tag("tab." + name.lowercase())

    /**
     * iOS `tabBars.buttons[name].tap()` (and the tour's `openTab`, which retries while a sheet is still dismissing):
     * clicks the tab until it is the selected one.
     */
    fun selectTab(name: String) {
        val tab = tab(name)
        repeat(3) {
            waitUntil(4_000) { isHittable(tab) }
            click(tab)
            if (waitUntil(3_000) { tab.locate(this)?.isSelected == true }) return
        }
        failWithScreen("The $name tab could not be selected")
    }

    /** iOS `secureTextFields.firstMatch`: any password field the app draws (tagged by its English kind). */
    fun anyPasswordField(): Target = Target("any password field") { ui ->
        PASSWORD_KINDS.firstNotNullOfOrNull { ui.device.findObject(By.res(it)) }
    }

    fun exists(target: Target): Boolean = target.locate(this) != null

    fun find(target: Target): UiObject2 = target.locate(this) ?: failWithScreen("Missing $target")

    // -----------------------------------------------------------------------------------------------------------------
    // Waits (conditions, never fixed sleeps)

    /** Polls [condition] until it holds or [timeoutMs] passes; true when it held. */
    fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            dismissForeignAnrDialog()
            val held = try {
                condition()
            } catch (e: StaleObjectException) {
                false
            }
            if (held) return true
            if (SystemClock.uptimeMillis() >= deadline) return false
            SystemClock.sleep(POLL_MS)
        }
    }

    /** iOS `waitForExistence(timeout:)`. */
    fun waitForExistence(target: Target, timeoutMs: Long = 5_000): Boolean = waitUntil(timeoutMs) { exists(target) }

    /** Waits for [target] and returns it, or fails with the screen's description. */
    fun waitFor(target: Target, timeoutMs: Long = 5_000, message: String? = null): UiObject2 {
        var found: UiObject2? = null
        waitUntil(timeoutMs) { target.locate(this)?.also { found = it } != null }
        return found ?: failWithScreen(message ?: "Timed out after $timeoutMs ms waiting for $target")
    }

    /** iOS `waitForNonExistence(timeout:)`. */
    fun waitForNonExistence(target: Target, timeoutMs: Long = 5_000): Boolean = waitUntil(timeoutMs) { !exists(target) }

    /** iOS `waitUntilEnabled`. */
    fun waitUntilEnabled(target: Target, timeoutMs: Long = 20_000): Boolean =
        waitUntil(timeoutMs) { target.locate(this)?.isEnabled == true }

    // -----------------------------------------------------------------------------------------------------------------
    // Scrolling and the visible band

    /**
     * The page's main vertical scroller: the largest scrollable node of the top-most app window (a sheet or dialog above
     * the page is what people swipe), else of any app window.
     */
    fun mainScrollable(): UiObject2? {
        val candidates = device.findObjects(By.scrollable(true).pkg(SignerApp.PACKAGE)).filter {
            val r = it.safeBounds()
            r.height() > device.displayHeight / 6 && r.height() >= r.width() / 3
        }
        val top = topAppWindowId()
        val inTop = candidates.filter { c ->
            try {
                c.accessibilityNodeInfo.windowId == top
            } catch (e: StaleObjectException) {
                false
            }
        }
        return inTop.ifEmpty { candidates }.maxByOrNull { it.safeBounds().let { r -> r.width() * r.height() } }
    }

    private fun topAppWindowId(): Int? = automation.windows
        .filter { it.root?.packageName == SignerApp.PACKAGE }
        .maxByOrNull { it.layer }?.id

    /** iOS `app.swipeUp()`: moves the content up by most of the visible band, revealing what is below. */
    fun swipeUp() = drag(revealBelow = true, fraction = PAGE_FRACTION)

    /** iOS `app.swipeDown()`: moves the content down by most of the visible band, revealing what is above. */
    fun swipeDown() = drag(revealBelow = false, fraction = PAGE_FRACTION)

    /**
     * A drag inside the scroller's visible band (never starting on a pinned bar), held still before release so the
     * list does not fling: every scroll moves by a known distance.
     */
    private fun drag(revealBelow: Boolean, fraction: Float) {
        val scroller = mainScrollable()
        val node = try {
            scroller?.accessibilityNodeInfo
        } catch (e: StaleObjectException) {
            null
        }
        val band = node?.let(::visibleBand) ?: systemBand()
        val bounds = scroller?.safeBounds()?.takeIf { !it.isEmpty } ?: Rect(0, 0, device.displayWidth, device.displayHeight)
        val x = bounds.centerX()
        val height = band.last - band.first
        val inset = (height * 0.1f).toInt()
        val distance = (height * fraction).toInt().coerceAtMost(height - 2 * inset)
        val from = if (revealBelow) band.last - inset else band.first + inset
        val to = if (revealBelow) from - distance else from + distance
        device.swipe(arrayOf(Point(x, from), Point(x, to), Point(x, to)), DRAG_STEPS)
        device.waitForIdle(1_500)
    }

    /** The screen between the status bar and the navigation bar, and above the IME. */
    private fun systemBand(): IntRange {
        var top = 0
        var bottom = device.displayHeight
        for (window in automation.windows) {
            val b = Rect().also(window::getBoundsInScreen)
            when (window.type) {
                AccessibilityWindowInfo.TYPE_SYSTEM -> if (b.top <= 0 && b.height() < device.displayHeight / 4) {
                    top = maxOf(top, b.bottom)
                } else if (b.bottom >= device.displayHeight - 1 && b.height() < device.displayHeight / 4) {
                    bottom = minOf(bottom, b.top)
                }
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> if (!b.isEmpty) bottom = minOf(bottom, b.top)
            }
        }
        return top..bottom
    }

    /**
     * The vertical band of [scroller] that is really visible: inside the system bars and the IME, and between the
     * pinned parts of its own window (the top app bar above, a pinned bottom bar or the tab bar below).
     */
    private fun visibleBand(scroller: AccessibilityNodeInfo): IntRange {
        val system = systemBand()
        val sb = Rect().also(scroller::getBoundsInScreen)
        var top = maxOf(sb.top, system.first)
        var bottom = minOf(sb.bottom, system.last)
        val chain = ancestors(scroller)
        val root = chain.lastOrNull() ?: return top..bottom
        val upper = sb.top + sb.height() / 4
        val lower = sb.top + sb.height() * 6 / 10
        var visited = 0
        fun visit(node: AccessibilityNodeInfo) {
            if (++visited > MAX_BAND_NODES || node == scroller) return
            if (node !in chain && node.isVisibleToUser) {
                val b = Rect().also(node::getBoundsInScreen)
                val overlaps = b.right > sb.left && b.left < sb.right
                if (!b.isEmpty && overlaps && b.height() < sb.height() / 2) {
                    if (b.centerY() < upper) top = maxOf(top, b.bottom) else if (b.centerY() > lower) bottom = minOf(bottom, b.top)
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        visit(root)
        return top..maxOf(top, bottom)
    }

    private fun ancestors(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val chain = mutableListOf<AccessibilityNodeInfo>()
        var current = node.parent
        while (current != null && chain.size < 60) {
            chain += current
            current = current.parent
        }
        return chain
    }

    /** The scroller (a vertical list or page) that [node] scrolls with, if any. */
    private fun scrollerOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = ancestors(node).firstOrNull {
        it.isScrollable && Rect().also(it::getBoundsInScreen).height() > device.displayHeight / 6
    }

    /** Where [target] is relative to its visible band: -1 above (under a top bar), 0 inside, +1 below; null if missing. */
    private fun position(target: Target): Int? {
        val obj = target.locate(this) ?: return null
        return try {
            val r = obj.visibleBounds
            val band = scrollerOf(obj.accessibilityNodeInfo)?.let(::visibleBand) ?: systemBand()
            when {
                r.isEmpty -> null
                r.centerY() <= band.first + EDGE_PX -> -1
                r.centerY() >= band.last - EDGE_PX -> 1
                else -> 0
            }
        } catch (e: StaleObjectException) {
            null
        }
    }

    /** iOS `scrollToExistence`: lazy lists only compose visible rows, so an off-screen row exists only after scrolling. */
    fun scrollToExistence(target: Target): Boolean {
        if (waitForExistence(target, 3_000)) return true
        repeat(6) {
            swipeUp()
            if (waitForExistence(target, 1_000)) return true
        }
        repeat(10) {
            swipeDown()
            if (waitForExistence(target, 1_000)) return true
        }
        return false
    }

    /**
     * Whether [condition] holds anywhere on the current page. XCUITest's `exists` also sees what a scroll view holds off
     * screen, UiAutomator only what is on screen (Compose reports no node outside the viewport), so an "is not there"
     * check of iOS searches the page: from where it is up to the top, then down to the end (at most [maxSwipes] each
     * way; a lazy list that pages in more rows ends there). When nothing is found the page is brought back to where it
     * was (to the top, then as many drags down as it took to get there; drags never fling), so the steps after the
     * check scroll as they would without it; when something is found it stays on screen for the failure's description.
     * Only for pages, never on a bottom sheet, where a drag at the top would pull the sheet down.
     */
    fun anywhereOnPage(maxSwipes: Int = 12, condition: () -> Boolean): Boolean {
        if (condition()) return true
        val up = PageSearch()
        val down = PageSearch()
        val found = scrollUntilStill(revealBelow = false, maxSwipes, up, condition) ||
            scrollUntilStill(revealBelow = true, maxSwipes, down, condition)
        // How far the search went, for the test's logcat (an end reached is what makes an absence meaningful).
        Log.i(LOG_TAG, "Page search: up ${up.describe()}; down ${down.describe()}")
        if (!found) {
            scrollUntilStill(revealBelow = false, maxSwipes + 2, PageSearch()) { false }
            repeat(up.drags - if (up.endReached) 1 else 0) { drag(revealBelow = true, fraction = PAGE_FRACTION) }
        }
        return found
    }

    /** One direction of [anywhereOnPage]. */
    private class PageSearch {
        var drags = 0
        var endReached = false
        var found = false

        fun describe() = "$drags drag(s), " + when {
            found -> "found"
            endReached -> "end reached"
            else -> "stopped at the limit"
        }
    }

    /** Page-sized drags in one direction until [found] holds (true) or the page stops moving (false). */
    private fun scrollUntilStill(revealBelow: Boolean, maxSwipes: Int, search: PageSearch, found: () -> Boolean): Boolean {
        repeat(maxSwipes) {
            val before = pagePosition()
            drag(revealBelow, PAGE_FRACTION)
            search.drags++
            if (found()) {
                search.found = true
                return true
            }
            if (pagePosition() == before) {
                search.endReached = true
                return false
            }
        }
        return false
    }

    /**
     * Where the page's content sits: the tags and tops of the main scroller's labelled nodes (fresh from the app).
     * Texts are left out, so an animated QR code's changing part counter never reads as movement.
     */
    private fun pagePosition(): List<String> {
        val scroller = mainScrollable() ?: return emptyList()
        val root = try {
            scroller.accessibilityNodeInfo
        } catch (e: StaleObjectException) {
            return emptyList()
        }
        val parts = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (parts.size > MAX_BAND_NODES || depth > 30) return
            if (depth > 0) node.refresh()
            if (node.text != null || node.contentDescription != null || node.viewIdResourceName != null) {
                parts += "${node.viewIdResourceName.orEmpty()}@${Rect().also(node::getBoundsInScreen).top}"
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return parts
    }

    /** iOS `scrollClear(of:_:)`: scrolls until [target] sits fully above [bar] (a control of a pinned bottom bar). */
    fun scrollClear(bar: Target, target: Target) {
        if (!scrollToExistence(target)) failWithScreen("Missing $target")
        repeat(4) {
            val barTop = bar.locate(this)?.safeBounds()?.top ?: return
            val bottom = target.locate(this)?.safeBounds()?.bottom ?: return
            if (bottom <= barTop - CLEARANCE_PX) return
            drag(revealBelow = true, fraction = NUDGE_FRACTION)
        }
    }

    /** Brings [target] into its visible band with short drags in the right direction. */
    private fun bringIntoBand(target: Target) {
        repeat(8) {
            when (position(target)) {
                0 -> return
                -1 -> drag(revealBelow = false, fraction = NUDGE_FRACTION)
                1 -> drag(revealBelow = true, fraction = NUDGE_FRACTION)
                null -> if (!scrollToExistence(target)) return
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Actions

    /**
     * iOS `tap(_:in:)`: scroll to the object; with the IME up, put it away first (it may cover the object); bring it
     * clear of the pinned bars; then require it to be on screen and enabled, and click it.
     */
    fun tap(target: Target) {
        if (!scrollToExistence(target)) failWithScreen("Missing element: $target")
        if (!isHittable(target)) dismissKeyboard()
        if (!isHittable(target)) bringIntoBand(target)
        if (!isHittable(target)) failWithScreen("Element is not hittable: $target")
        if (!waitUntil(5_000) { target.locate(this)?.isEnabled == true }) failWithScreen("Element is not enabled: $target")
        click(target)
    }

    /** A plain click on [target] where it is now (iOS `element.tap()` without the scrolling of `tap(_:in:)`). */
    fun click(target: Target) {
        waitFor(target, 5_000)
        val deadline = SystemClock.uptimeMillis() + CLICK_TIMEOUT_MS
        while (true) {
            // A page in transition, or a list still filling in, replaces its nodes: click a node that has stayed put.
            val obj = stableObject(target) ?: waitFor(target, 2_000)
            try {
                obj.click()
                device.waitForIdle()
                return
            } catch (e: StaleObjectException) {
                if (SystemClock.uptimeMillis() > deadline) failWithScreen("$target kept changing under the click")
            }
        }
    }

    /** [target]'s node once the same node has survived a poll interval (it is not being replaced), or null. */
    private fun stableObject(target: Target): UiObject2? {
        var previous: UiObject2? = null
        var stable: UiObject2? = null
        waitUntil(STABLE_TIMEOUT_MS) {
            val earlier = previous
            previous = target.locate(this)
            if (earlier != null && !isStale(earlier)) {
                stable = earlier
                true
            } else {
                false
            }
        }
        return stable
    }

    /** On screen with a usable area, clear of the IME and of the pinned bars over its scroller. */
    fun isHittable(target: Target): Boolean {
        val obj = target.locate(this) ?: return false
        val r = obj.safeBounds()
        if (r.isEmpty || r.height() < MIN_HIT_PX || r.width() < MIN_HIT_PX) return false
        val display = Rect(0, 0, device.displayWidth, device.displayHeight)
        if (!display.contains(r.centerX(), r.centerY())) return false
        val ime = imeBounds()
        if (ime != null && ime.contains(r.centerX(), r.centerY())) return false
        return position(target) == 0
    }

    /** The system keyboard's bounds while it is shown (gaps.md G1.0 "IME shown"), else null. */
    fun imeBounds(): Rect? = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { Rect().also(it::getBoundsInScreen) }
        ?.takeIf { !it.isEmpty }

    fun isKeyboardShown(): Boolean = imeBounds() != null

    /**
     * iOS `dismissKeyboard`: tapping the top bar's title puts the keyboard away without changing anything. Never
     * `pressBack()` without a keyboard (it would navigate); with the keyboard up, Back only closes the keyboard.
     */
    fun dismissKeyboard() {
        if (!isKeyboardShown()) return
        val title = topHeading()
        if (title != null) {
            try {
                title.click()
            } catch (e: StaleObjectException) {
                // Re-checked below.
            }
        }
        if (!waitUntil(2_000) { !isKeyboardShown() }) {
            device.pressBack()
            waitUntil(2_000) { !isKeyboardShown() }
        }
    }

    /** The top-most heading in the upper part of the screen (the page or sheet title), if any. */
    private fun topHeading(): UiObject2? {
        val limit = device.displayHeight / 4
        return device.findObjects(By.pkg(SignerApp.PACKAGE).text(Pattern.compile(".+", Pattern.DOTALL)))
            .filter { it.isHeading() && it.safeBounds().let { r -> !r.isEmpty && r.centerY() < limit } }
            .minByOrNull { it.safeBounds().top }
    }

    /**
     * iOS `type(_:into:)`: click the field, then set its text (UiAutomator replaces where XCUITest appends; every caller
     * types into an empty or to-be-replaced field). A password field reports one bullet per character, so the length is
     * checked instead of the text.
     */
    fun type(text: String, into: Target) {
        tap(into)
        setText(into, text)
    }

    /** Sets [text] without focusing the field first (no keyboard comes up), and checks that it arrived whole. */
    fun setText(target: Target, text: String) {
        waitFor(target, 5_000)
        editable(target).text = text
        val arrived = waitUntil(3_000) { textOf(target).let { now -> now == text || isMaskOf(now, text) } }
        if (!arrived) failWithScreen("The text could not be set whole into $target (${textOf(target).length} of ${text.length} characters)")
    }

    /**
     * iOS `typePayload`: sets a long QR payload and checks it arrived whole, retrying twice (UiAutomator's set text does
     * not drop characters, so the retry is only a safety net).
     */
    fun typePayload(text: String, into: Target) {
        waitFor(into, 5_000)
        repeat(3) {
            editable(into).text = text
            if (waitUntil(2_000) { textOf(into) == text }) return
        }
        failWithScreen("The payload could not be typed whole: ${textOf(into).length} of ${text.length} characters.")
    }

    /** iOS `clear(_:count:)`: empties the field. */
    fun clear(target: Target) {
        waitFor(target, 5_000)
        editable(target).clear()
        waitUntil(2_000) { textOf(target).none { it == BULLET } }
    }

    /** The field's text as accessibility reports it (a password field: one bullet per character). */
    fun textOf(target: Target): String = try {
        target.locate(this)?.let { editableOrSelf(it).text }.orEmpty()
    } catch (e: StaleObjectException) {
        ""
    }

    /** The editable node of [target]: the node itself, or the text field inside a tagged wrapper. */
    private fun editable(target: Target): UiObject2 = editableOrSelf(find(target))

    private fun editableOrSelf(obj: UiObject2): UiObject2 = if (obj.accessibilityNodeInfo.isEditable) {
        obj
    } else {
        obj.findObject(By.clazz("android.widget.EditText")) ?: obj
    }

    private fun isMaskOf(shown: String, text: String) = shown.isNotEmpty() && shown.all { it == BULLET } && shown.length == text.length

    /** iOS `setSwitch`: clicks until the switch reports [on]. */
    fun setSwitch(target: Target, on: Boolean) {
        val toggle = waitFor(target, 5_000)
        if (toggle.isChecked == on) return
        tap(target)
        if (!waitUntil(3_000) { target.locate(this)?.isChecked == on }) {
            click(target)
        }
        if (!waitUntil(3_000) { target.locate(this)?.isChecked == on }) failWithScreen("$target did not turn ${if (on) "on" else "off"}")
    }

    /** iOS `goBack`: the top bar's navigation icon (`nav.back`), never `pressBack()`. */
    fun goBack() {
        val back = tag(BACK_TAG)
        repeat(2) {
            val arrow = waitFor(back, 5_000)
            try {
                arrow.click()
            } catch (e: StaleObjectException) {
                return@repeat // The page changed under the click: look again.
            }
            // The page that had this arrow goes away (its nodes go stale). A click that landed while the page was still
            // settling is not taken: click once more.
            if (waitUntil(3_000) { isStale(arrow) }) {
                device.waitForIdle()
                return
            }
        }
        failWithScreen("Back did not leave the page")
    }

    private fun isStale(obj: UiObject2): Boolean = try {
        obj.visibleBounds
        false
    } catch (e: StaleObjectException) {
        true
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Reads

    /**
     * Every text and content description of [obj] and its descendants, in order (Compose keeps a merged node's texts on
     * its children). Each node is refreshed from the app, so a label never comes from a stale cache.
     */
    fun label(obj: UiObject2): String = try {
        obj.accessibilityNodeInfo.collectLabel()
    } catch (e: StaleObjectException) {
        ""
    }

    fun label(target: Target): String = label(find(target))

    /** iOS `.value` of a cell or chip: the accessibility state description (API 30+). */
    fun stateDescription(target: Target): String? = find(target).accessibilityNodeInfo.stateDescription?.toString()

    /** Selected (tabs, segments, chain buttons: `Role.Tab`) or checked (other selectables, toggles). */
    fun isSelected(target: Target): Boolean = find(target).let { it.isSelected || it.isChecked }

    /** The [index]th segment label of the segmented control tagged [tag] (iOS `segmentedControls[...].buttons.element(boundBy:)`). */
    fun segmentLabel(tag: String, index: Int): String? = segments(tag).getOrNull(index)?.let { label(it) }

    /** The segment of the control tagged [tag] whose label is [label]. */
    fun segment(tag: String, label: String) = Target("segment '$label' of '$tag'") { ui ->
        ui.segments(tag).firstOrNull { ui.label(it) == label }
    }

    /** The segments of a control: every clickable or selected child (the selected one is not clickable), left to right. */
    private fun segments(tag: String): List<UiObject2> {
        val control = device.findObject(By.res(tag)) ?: return emptyList()
        return control.children.filter { child ->
            try {
                child.accessibilityNodeInfo.collectLabel().isNotEmpty() && (child.isClickable || child.isSelected || child.isCheckable)
            } catch (e: StaleObjectException) {
                false
            }
        }.sortedBy { it.safeBounds().left }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Diagnostics

    /** The current screen (every window, sensitive values redacted), for failure messages. */
    fun describeScreen(): String {
        val foreground = try {
            device.currentPackageName
        } catch (e: Exception) {
            null
        }
        val note = if (foreground != null && foreground != SignerApp.PACKAGE) {
            "NOTE: another app ($foreground) is in the foreground; something outside the suite may have launched it.\n"
        } else {
            ""
        }
        return note + Hierarchy.describe(device, automation)
    }

    fun failWithScreen(message: String): Nothing {
        fail("$message\n--- screen ---\n${describeScreen()}")
        throw AssertionError(message)
    }

    /**
     * Another app's "isn't responding" dialog (seen on the shared emulator) would cover the app: wait for it. An ANR
     * of BitTool Signer itself is a defect and is left for the test to fail on.
     */
    private fun dismissForeignAnrDialog() {
        val wait = device.findObject(By.res("android:id/aerr_wait")) ?: return
        val about = device.findObject(By.res("android:id/alertTitle"))?.text.orEmpty()
        if (about.contains("BitTool Signer")) return
        try {
            wait.click()
        } catch (e: StaleObjectException) {
            // Gone already.
        }
    }

    companion object {
        const val BACK_TAG = "nav.back"
        private const val LOG_TAG = "SignerE2E"
        const val BULLET = '•'
        private const val POLL_MS = 150L
        private const val IDLE_TIMEOUT_MS = 1_000L
        private const val PAGE_FRACTION = 0.8f
        private const val NUDGE_FRACTION = 0.3f
        private const val DRAG_STEPS = 25
        private const val EDGE_PX = 4
        private const val CLEARANCE_PX = 60
        private const val MIN_HIT_PX = 8
        private const val MAX_BAND_NODES = 600
        private const val CLICK_TIMEOUT_MS = 15_000L
        private const val STABLE_TIMEOUT_MS = 5_000L

        /** `PasswordKind.english` of `:core:ui`: the test tag of every password field. */
        val PASSWORD_KINDS = listOf(
            "Wallet password", "Confirm password", "Current wallet password", "New password", "Confirm new password",
            "BIP39 passphrase", "Confirm passphrase",
        )

        fun prefixPattern(prefix: String): Pattern = Pattern.compile(Pattern.quote(prefix) + ".*", Pattern.DOTALL)

        fun containsPattern(text: String): Pattern =
            Pattern.compile(".*" + Pattern.quote(text) + ".*", Pattern.DOTALL or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    }
}

fun UiObject2.safeBounds(): Rect = try {
    visibleBounds
} catch (e: StaleObjectException) {
    Rect()
}

fun UiObject2.isHeading(): Boolean = try {
    accessibilityNodeInfo.isHeading
} catch (e: StaleObjectException) {
    false
}

/** Texts and content descriptions of this node and its descendants (each refreshed from the app), joined by spaces. */
fun AccessibilityNodeInfo.collectLabel(): String {
    val parts = mutableListOf<String>()
    fun walk(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 0) node.refresh()
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
        if (depth > 30) return
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
    }
    walk(this, 0)
    return parts.joinToString(" ")
}
