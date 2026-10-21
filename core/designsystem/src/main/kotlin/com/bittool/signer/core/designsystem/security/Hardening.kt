package com.bittool.signer.core.designsystem.security

import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.unit.IntSize
import java.util.WeakHashMap

// ---------------------------------------------------------------------------------------------------------------------
// Tapjacking guard (gaps.md G4.2, DECISIONS §9.7, ARCHITECTURE §4.8)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Refuses a touch that reached the app through another app's window drawn over it: the `ACTION_DOWN` of a gesture
 * whose `MotionEvent` carries `FLAG_WINDOW_IS_OBSCURED` (another app's window covers the touched point) is consumed
 * together with the rest of that gesture, and [onBlocked] is called once (show the `security_overlay_blocked` toast
 * with `SignerIcons.WarningFilled` and `HapticKind.Warning`: it reports a refusal, not a success).
 *
 * The check runs in the initial pass, before the decorated control sees the event, so the click never happens. Put it
 * on decisive controls only (Sign, the risk acknowledgement, reveal, delete, confirm; ARCHITECTURE §4.8), never on the
 * root: screen-dimmer apps would otherwise make the whole app unresponsive. On API 31+ `:app` and every dialog / sheet
 * also hide foreign overlays (`HideOverlayWindowsEffect`); this guard covers API 26–30 and anything that slips through.
 * Accessibility actions (TalkBack double tap) are not touches and are not affected.
 */
fun Modifier.obscuredTouchGuard(onBlocked: () -> Unit): Modifier = this then ObscuredTouchGuardElement(onBlocked)

/**
 * Whether `MotionEvent.flags` say another app's window covered the touched point (pure, unit-tested).
 * `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` is deliberately not refused: every input method (e.g. Huawei's secure keyboard)
 * is another app's window and sets it whenever it is shown, which made decisive buttons untappable with the keyboard
 * up. Foreign overlays are hidden on API 31+ anyway (`HideOverlayWindowsEffect`).
 */
@Suppress("UNUSED_PARAMETER")
fun isObscuredTouch(flags: Int, sdk: Int = Build.VERSION.SDK_INT): Boolean =
    flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0

private data class ObscuredTouchGuardElement(val onBlocked: () -> Unit) : ModifierNodeElement<ObscuredTouchGuardNode>() {
    override fun create() = ObscuredTouchGuardNode(onBlocked)
    override fun update(node: ObscuredTouchGuardNode) {
        node.onBlocked = onBlocked
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "obscuredTouchGuard"
    }
}

private class ObscuredTouchGuardNode(var onBlocked: () -> Unit) : Modifier.Node(), PointerInputModifierNode {
    /** True from a refused `ACTION_DOWN` until every pointer of that gesture is up. */
    private var blocking = false

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        val motion = pointerEvent.motionEvent
        if (!blocking && motion != null && motion.actionMasked == MotionEvent.ACTION_DOWN && isObscuredTouch(motion.flags)) {
            blocking = true
            onBlocked()
        }
        if (blocking) {
            pointerEvent.changes.forEach { it.consume() }
            if (pointerEvent.changes.none { it.pressed }) blocking = false
        }
    }

    override fun onCancelPointerInput() {
        blocking = false
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Accessibility-sensitive data (gaps.md G5.1)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Whether [AccessibilitySensitive] marks views, and components mark their own secret nodes (`isSensitiveData`, e.g.
 * every `PasswordField`). `true` by default; `:app` may provide `false` in a debug UI-test run if UiAutomator cannot
 * read sensitive nodes (gaps.md G5.3). Never `false` in release. (Checked for G5.3: Android 14's
 * `UiAutomationConnection` registers UiAutomation as an accessibility tool unless `FLAG_NOT_ACCESSIBILITY_TOOL` is
 * passed, so `uiautomator dump` and UiAutomator tests see marked nodes without this switch.)
 */
val LocalSensitiveDataMarking: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

/**
 * While [active], marks the hosting view `accessibilityDataSensitive = YES` (API 34+), so only accessibility services
 * that declare `isAccessibilityTool` (TalkBack, Switch Access, Voice Access) can read or act on it; the previous value
 * comes back on dispose. Use it wherever a secret is on screen: revealed word grids and cards, the revealed recovery
 * QR, the verification board, a non-empty phrase editor, a visible passphrase, the authorize sheet (ARCHITECTURE §4.8).
 * Dialogs and sheets are separate windows: call it inside them. Reference-counted per view. Nothing exists below API
 * 34; `FLAG_SECURE` and the immediate conceal on pause remain the protection there.
 *
 * The pinned Compose also offers the per-node `Modifier.semantics { isSensitiveData = true }` (gaps G5.1 asked to
 * check): it hides that one node and its events from non-tool services. Components that draw a secret set it on the
 * secret's own node as well (every `PasswordField` does, which also reports bullets instead of a hidden password), but
 * the view-level marking stays the rule because it also covers the text around a secret and every event of the window.
 * UiAutomation connects as an accessibility tool on API 34+ (unless asked not to), so UI tests still see marked nodes.
 */
@Composable
fun AccessibilitySensitive(active: Boolean) {
    val view = LocalView.current
    val enabled = active && LocalSensitiveDataMarking.current && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    DisposableEffect(view, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        SensitiveMarking.acquire(view)
        onDispose { SensitiveMarking.release(view) }
    }
}

/** Reference-counted `accessibilityDataSensitive` (main thread only, like every view access). */
internal val SensitiveMarking = RefCountedViewAttribute(
    // The platform has no getter for the raw value; a view already sensitive is restored to YES, anything else to AUTO.
    read = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && it.isAccessibilityDataSensitive) {
            View.ACCESSIBILITY_DATA_SENSITIVE_YES
        } else {
            View.ACCESSIBILITY_DATA_SENSITIVE_AUTO
        }
    },
    write = { view, value -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) view.setAccessibilityDataSensitive(value) },
    held = View.ACCESSIBILITY_DATA_SENSITIVE_YES,
)

// ---------------------------------------------------------------------------------------------------------------------
// Keyboard and Autofill (DECISIONS §6, design-system §9.5, ownbit-ui-patterns §5.2)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Keeps the Android Autofill framework away from every field of the hosting composition while composed: the host gets
 * `importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`, so no "Save password?", generated-password or
 * fill offer reaches a secret field (a generated BIP39 passphrase would silently be a different wallet; design-system
 * §9.13 item 6). Compose ≥ 1.8 reports fields through semantics, so secret fields also never set a `contentType`.
 *
 * The flag goes on the **parent** of the Compose view (the `ComposeView`, or a dialog's layout): `AndroidComposeView`
 * reports `IMPORTANT_FOR_AUTOFILL_YES` whatever is set on it, while `View.isImportantForAutofill()` honours an
 * excluding ancestor. Reference-counted per host; inside a dialog or sheet it applies to that window's host.
 */
@Composable
fun ExcludeFromAutofill(enabled: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val host = autofillHost(view)
        AutofillExclusion.acquire(host)
        onDispose { AutofillExclusion.release(host) }
    }
}

/** The view whose autofill importance governs a composition: the Compose view's parent when it has one. */
internal fun autofillHost(composeView: View): View = composeView.parent as? View ?: composeView

internal val AutofillExclusion = RefCountedViewAttribute(
    read = { it.importantForAutofill },
    write = { view, value -> view.importantForAutofill = value },
    held = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
)

/**
 * Every text field inside [content] asks the keyboard not to learn from what is typed
 * (`EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`, an "incognito" session): passwords, passphrases, the recovery-phrase
 * editor, the wallet name. Android cannot refuse third-party keyboards as iOS does; this flag is what remains
 * (setup-backup §15.8). It wraps the platform input request, so it works for any `BasicTextField` and `TextFieldState`.
 *
 * Compose exposes input interception only as a composable scope (`InterceptPlatformTextInput`), not as a modifier,
 * hence this wrapper rather than the `Modifier.noPersonalizedLearning()` named in ARCHITECTURE §1.10.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(interceptor = NoPersonalizedLearningInterceptor, content = content)
}

/** Adds the no-learning flag to [editorInfo] (pure, unit-tested). */
fun applyNoPersonalizedLearning(editorInfo: EditorInfo) {
    editorInfo.imeOptions = editorInfo.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
}

@OptIn(ExperimentalComposeUiApi::class)
private object NoPersonalizedLearningInterceptor : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        val incognito = object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                val connection = request.createInputConnection(outAttributes)
                applyNoPersonalizedLearning(outAttributes)
                return connection
            }
        }
        nextHandler.startInputMethod(incognito)
    }
}

// ---------------------------------------------------------------------------------------------------------------------

/**
 * A view attribute held at [held] while at least one composable asks for it, then restored to the value it had before
 * the first request. Two screens doing it during a transition never undo each other.
 */
internal class RefCountedViewAttribute(
    private val read: (View) -> Int,
    private val write: (View, Int) -> Unit,
    private val held: Int,
) {
    private class Entry(val original: Int, var count: Int)

    private val entries = WeakHashMap<View, Entry>()

    @MainThread
    fun acquire(view: View) {
        val entry = entries[view]
        if (entry != null) {
            entry.count++
            return
        }
        entries[view] = Entry(read(view), 1)
        write(view, held)
    }

    @MainThread
    fun release(view: View) {
        val entry = entries[view] ?: return
        entry.count--
        if (entry.count > 0) return
        entries.remove(view)
        write(view, entry.original)
    }

    /** Number of active holders for [view] (tests). */
    internal fun holds(view: View): Int = entries[view]?.count ?: 0
}
