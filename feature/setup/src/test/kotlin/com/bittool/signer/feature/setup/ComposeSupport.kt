package com.bittool.signer.feature.setup

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.text.contextmenu.data.ProcessTextKey
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.security.RecoverySessionFactory
import com.bittool.signer.feature.setup.ui.LocalRecoverySessions

/** The looks every screen is recorded in (design-system §9.14). */
enum class Look(val dark: Boolean, val fontScale: Float, val rtl: Boolean) {
    Light(false, 1f, false),
    Dark(true, 1f, false),
    FontScale2(false, 2f, false),
    Rtl(true, 1f, true),
}

/** The app theme on the page colour, with the recovery sessions the backup components need. */
@Composable
fun SetupTestHost(sessions: RecoverySessionFactory, look: Look = Look.Light, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    SignerTheme(darkTheme = look.dark, applySystemBarAppearance = false) {
        CompositionLocalProvider(
            LocalRecoverySessions provides sessions,
            LocalDensity provides Density(density.density, look.fontScale),
            LocalLayoutDirection provides if (look.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            Box(Modifier.fillMaxSize().background(SignerTheme.colors.page)) { content() }
        }
    }
}

/**
 * The text-menu filters (`Modifier.filterTextContextMenuComponents`) a text field really carries, read from its
 * modifier chain: Compose builds the field's selection menu through them.
 */
fun SemanticsNodeInteraction.textMenuFilters(): List<(TextContextMenuComponent) -> Boolean> =
    fetchSemanticsNode().layoutInfo.getModifierInfo()
        .mapNotNull { it.modifier as? InspectableValue }
        .filter { it.nameFallback == "filterTextContextMenuDataComponents" }
        .map { element ->
            @Suppress("UNCHECKED_CAST")
            element.inspectableElements.first { it.name == "filter" }.value as (TextContextMenuComponent) -> Boolean
        }

/**
 * Whether a text field with [filters] offers an item keyed [key]. The keys a Compose text field's menu can hold:
 * [TextContextMenuKeys], one [ProcessTextKey] per installed PROCESS_TEXT action, and private ones (smart selection).
 */
fun offers(filters: List<(TextContextMenuComponent) -> Boolean>, key: Any): Boolean =
    filters.all { filter -> filter(TextContextMenuItem(key, label = "item") {}) }

/** The key Compose gives the n-th PROCESS_TEXT item ("Translate", "Search"…; its constructor is internal to Compose). */
fun processTextKey(index: Int): ProcessTextKey =
    ProcessTextKey::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(index)

/**
 * The node's bounds in the root, not clipped (`boundsInRoot` is clipped to the scrolling viewport, so a node scrolled
 * out of view, or partly under a bar outside the viewport, reports only its visible part).
 */
fun SemanticsNodeInteraction.unclippedBounds(): Rect = fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }

/**
 * Raises (or with 0 removes) a software keyboard [heightPx] tall, as the window reports it to Compose's
 * `WindowInsets.ime`: the insets are applied to the activity's content views, the way the platform delivers them.
 */
fun ComponentActivity.setKeyboardHeight(heightPx: Int) {
    val insets = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, heightPx))
        .setVisible(WindowInsetsCompat.Type.ime(), heightPx > 0)
        .build()
    val content = findViewById<ViewGroup>(android.R.id.content)
    for (index in 0 until content.childCount) ViewCompat.dispatchApplyWindowInsets(content.getChildAt(index), insets)
}

/** Whether [filters] keep Paste and Select All only: no clipboard, no Autofill, no other app's text action. */
fun isSecretTextMenu(filters: List<(TextContextMenuComponent) -> Boolean>): Boolean {
    if (filters.isEmpty()) return false
    if (!offers(filters, TextContextMenuKeys.PasteKey) || !offers(filters, TextContextMenuKeys.SelectAllKey)) return false
    val leaking = listOf(TextContextMenuKeys.CopyKey, TextContextMenuKeys.CutKey, TextContextMenuKeys.AutofillKey, processTextKey(0), processTextKey(4), Any())
    return leaking.none { offers(filters, it) }
}
