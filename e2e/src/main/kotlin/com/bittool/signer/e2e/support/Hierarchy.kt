package com.bittool.signer.e2e.support

import android.app.UiAutomation
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.UiDevice

/**
 * A compact description of what is on screen, for failure messages: one line per meaningful node of every window
 * (tag, class, text, description, state, flags, bounds). Recovery words, word chips, card words, the phrase editor and
 * the recovery QR are **redacted**, with everything inside them (iOS: never put the words or word choices into
 * diagnostics); the suite only ever types public test values elsewhere.
 */
object Hierarchy {
    private const val MAX_LINES = 220

    /** Tags whose node and descendants may show recovery words. */
    private val SENSITIVE = listOf(
        "recovery.word.", "cards.card.", "verification.chip.", "verification.slot.", "recovery.input.visible",
        "backup.qr", "suggestion.", "recovery.suggestions",
    )

    fun describe(device: UiDevice, automation: UiAutomation): String = try {
        val lines = mutableListOf<String>()
        val windows = automation.windows
        if (windows.isNullOrEmpty()) {
            device.windowRoots.forEach { root -> dump(root, 0, false, lines) }
        } else {
            for (window in windows) {
                val bounds = Rect().also(window::getBoundsInScreen)
                lines += "[window type=${window.type} layer=${window.layer} title=${window.title} bounds=${bounds.toShortString()}]"
                window.root?.let { dump(it, 1, false, lines) }
                if (lines.size > MAX_LINES) break
            }
        }
        if (lines.size > MAX_LINES) (lines.take(MAX_LINES) + "… (${lines.size - MAX_LINES} more lines)").joinToString("\n") else lines.joinToString("\n")
    } catch (e: Exception) {
        "(screen could not be described: ${e.javaClass.simpleName})"
    }

    private fun dump(node: AccessibilityNodeInfo, depth: Int, redacted: Boolean, lines: MutableList<String>) {
        if (lines.size > MAX_LINES + 50) return
        // Fresh from the app, not from the accessibility cache (which can lag behind a text change).
        if (depth > 0) node.refresh()
        val id = node.viewIdResourceName.orEmpty()
        val sensitive = redacted || SENSITIVE.any { id.startsWith(it) }
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val state = node.stateDescription?.toString().orEmpty()
        val meaningful = id.isNotEmpty() || text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isScrollable
        if (meaningful && node.isVisibleToUser) {
            val b = Rect().also(node::getBoundsInScreen)
            val flags = buildList {
                if (node.isClickable) add("clickable")
                if (!node.isEnabled) add("disabled")
                // getChecked() (API 36) replaces it; the suite's devices are older.
                @Suppress("DEPRECATION")
                if (node.isChecked) add("checked")
                if (node.isSelected) add("selected")
                if (node.isScrollable) add("scrollable")
                if (node.isFocused) add("focused")
                if (node.isHeading) add("heading")
                if (node.isEditable) add("editable")
            }.joinToString(",")
            val shown = { value: String -> if (sensitive) "<redacted ${value.length}>" else value.replace("\n", "⏎").take(160) }
            lines += buildString {
                append("  ".repeat(depth.coerceAtMost(12)))
                append(node.className?.toString()?.substringAfterLast('.') ?: "?")
                if (id.isNotEmpty()) append(" #").append(id)
                if (text.isNotEmpty()) append(" text=\"").append(shown(text)).append('"')
                if (desc.isNotEmpty()) append(" desc=\"").append(shown(desc)).append('"')
                if (state.isNotEmpty()) append(" state=\"").append(shown(state)).append('"')
                if (flags.isNotEmpty()) append(" [").append(flags).append(']')
                append(' ').append(b.toShortString())
            }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { dump(it, depth + 1, sensitive, lines) }
    }
}
