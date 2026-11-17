package com.bittool.signer.core.designsystem.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.icu.text.ListFormatter
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.Tone
import java.util.Locale
import com.bittool.signer.core.l10n.R as L10n

// ---------------------------------------------------------------------------------------------------------------------
// Accessibility services below API 34 (2026-11-16 audit M-03)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Who could read a secret on screen through accessibility, below API 34. From API 34 [AccessibilitySensitive] keeps
 * every service that is not an accessibility tool away from marked views; Android 8–13 has nothing like it, and
 * `FLAG_SECURE` does not stop a service from reading the semantics tree. A malicious accessibility service the person
 * was talked into enabling could read revealed words, cards and phrase entry there.
 *
 * - [untrusted]: the names of enabled accessibility services that are not part of the phone's system image
 *   ([classifyScreenReaders]). Empty on API 34+.
 * - [screenReaderOn]: touch exploration is on (TalkBack or another screen reader), so someone may need the words read.
 */
@Immutable
data class ScreenReaders(val untrusted: List<String>, val screenReaderOn: Boolean) {
    /** Show [ScreenReaderWarning] before secrets: an app that is not part of the system can read the screen. */
    val warn: Boolean get() = untrusted.isNotEmpty()

    /**
     * Leave secret text out of the accessibility tree ([hiddenFromScreenReaders]): an untrusted service is on and no
     * screen reader needs the words. With a screen reader on, the words stay readable and the warning is the protection.
     */
    val hideSecrets: Boolean get() = untrusted.isNotEmpty() && !screenReaderOn

    companion object {
        val None = ScreenReaders(emptyList(), screenReaderOn = false)
    }
}

/** An enabled accessibility service, as [classifyScreenReaders] needs it. */
data class EnabledService(val name: String, val preinstalled: Boolean)

/**
 * The pure rule (unit-tested). On API 34+ always [ScreenReaders.None]. Below it, a service is untrusted unless its app
 * came with the system image (`FLAG_SYSTEM`, also when updated since): TalkBack, Switch Access, Select to Speak and the
 * manufacturer's screen reader ship that way. `isAccessibilityTool` is not used: below API 34 any app can declare it
 * and nothing checks it. Names are trimmed to [MAX_NAME] characters, blank ones dropped, duplicates merged.
 */
fun classifyScreenReaders(sdk: Int, services: List<EnabledService>, touchExploration: Boolean): ScreenReaders {
    if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return ScreenReaders.None
    val untrusted = services.filterNot { it.preinstalled }
        .map { it.name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME) }
        .map { it.ifEmpty { "?" } }
        .distinct()
    return ScreenReaders(untrusted, screenReaderOn = touchExploration)
}

private const val MAX_NAME = 40

/**
 * Overrides the system answer (tests and screenshot tours); null, the default, asks [AccessibilityManager]. Never set in
 * a release build.
 */
val LocalScreenReaders: ProvidableCompositionLocal<ScreenReaders?> = staticCompositionLocalOf { null }

/**
 * The current [ScreenReaders]: [LocalScreenReaders] when provided, otherwise read from [AccessibilityManager] now and
 * again when accessibility or touch exploration is switched, and on every resume (coming back from Settings ›
 * Accessibility after turning a service off).
 */
@Composable
fun rememberScreenReaders(): ScreenReaders {
    LocalScreenReaders.current?.let { return it }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return ScreenReaders.None
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var readers by remember(manager) { mutableStateOf(systemScreenReaders(context, manager)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { readers = systemScreenReaders(context, manager) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val onState = AccessibilityManager.AccessibilityStateChangeListener { readers = systemScreenReaders(context, manager) }
        val onTouch = AccessibilityManager.TouchExplorationStateChangeListener { readers = systemScreenReaders(context, manager) }
        manager.addAccessibilityStateChangeListener(onState)
        manager.addTouchExplorationStateChangeListener(onTouch)
        onDispose {
            manager.removeAccessibilityStateChangeListener(onState)
            manager.removeTouchExplorationStateChangeListener(onTouch)
        }
    }
    return readers
}

private fun systemScreenReaders(context: Context, manager: AccessibilityManager?): ScreenReaders {
    if (manager == null || !manager.isEnabled) return ScreenReaders.None
    val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty().map { info ->
        val service = info.resolveInfo?.serviceInfo
        val app = service?.applicationInfo
        val preinstalled = app != null && app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        // The service's own label, else its app's, else the package: whatever the phone can name without a query.
        val name = runCatching { info.resolveInfo?.loadLabel(context.packageManager)?.toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: service?.packageName.orEmpty()
        EnabledService(name, preinstalled)
    }
    return classifyScreenReaders(Build.VERSION.SDK_INT, services, manager.isTouchExplorationEnabled)
}

/**
 * Leaves this node and everything in it out of the accessibility tree while [hidden] ([ScreenReaders.hideSecrets]):
 * for text that is a secret (revealed words, card cells, the phrase being typed, completion chips). Pointer input is
 * unaffected; what is lost is reading and operating it through an accessibility service, which only happens while an
 * untrusted service is on and no screen reader is.
 */
fun Modifier.hiddenFromScreenReaders(hidden: Boolean): Modifier = if (hidden) clearAndSetSemantics {} else this

/**
 * The warning shown above revealed recovery words, cards, the recovery QR code, the verification board and phrase entry
 * while [ScreenReaders.warn]: names the apps and asks to turn them off unless trusted. Nothing otherwise.
 */
@Composable
fun ScreenReaderWarning(readers: ScreenReaders, modifier: Modifier = Modifier) {
    if (!readers.warn) return
    val names = remember(readers.untrusted) { ListFormatter.getInstance(Locale.getDefault()).format(readers.untrusted) }
    Callout(
        text = stringResource(L10n.string.security_screen_reader_warning, names),
        title = stringResource(L10n.string.security_screen_reader_warning_title),
        tone = Tone.Warning,
        modifier = modifier.testTag(SCREEN_READER_WARNING_TAG),
    )
}

const val SCREEN_READER_WARNING_TAG = "security.screenReaderWarning"
