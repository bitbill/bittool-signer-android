package com.bittool.signer.core.designsystem.theme

import android.app.Activity
import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Current [SignerColors]. Prefer [SignerTheme.colors]. */
val LocalSignerColors = staticCompositionLocalOf { SignerColors.Light }

/** Current [SignerTypography]. Prefer [SignerTheme.typography]. */
val LocalSignerTypography = staticCompositionLocalOf { SignerTypography.Default }

/**
 * `true` when the user turned animations off ("Remove animations", `ANIMATOR_DURATION_SCALE == 0`): springs lose their
 * bounce and decorative repeats are skipped (design-system §6.1, §9.11). Prefer [SignerTheme.reduceMotion].
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * The BitTool Signer theme: a fixed brand palette (no dynamic colour), the iOS type scale and the grouped-surface look
 * in Material 3 (design-system.md §9.2). Wrap the activity content once (`setContent { SignerTheme(dark) { … } }`);
 * nested uses (the always-dark scanner subtree, previews, screenshot tests) are fine.
 *
 * [darkTheme] defaults to the configuration's night mode. `:app` resolves the Settings › Theme choice itself (the
 * `:core:ui` overload `SignerTheme(appearance)` does it with [rememberSystemInDarkTheme] for "System", so a switch back
 * to System is seen even while AppCompat's night mode still carries the previous choice).
 *
 * When [applySystemBarAppearance] is true (default) and the composition is hosted in an activity window, the status
 * and navigation bar icons follow the theme (dark icons on the light page); the activity itself enables edge-to-edge.
 */
@Composable
fun SignerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    applySystemBarAppearance: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) SignerColors.Dark else SignerColors.Light
    val typography = SignerTypography.Default
    val reduceMotion = rememberSystemReduceMotion()
    val colorScheme = remember(colors) { colors.toColorScheme() }
    val materialTypography = remember(typography) { typography.toMaterial() }
    if (applySystemBarAppearance) SystemBarAppearance(darkIcons = !darkTheme)
    CompositionLocalProvider(
        LocalSignerColors provides colors,
        LocalSignerTypography provides typography,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = materialTypography, shapes = SignerShapes.material) {
            CompositionLocalProvider(LocalContentColor provides colors.label, content = content)
        }
    }
}

/**
 * Raises the palette one level for the content of a sheet or dialog (iOS dark "elevated" interface level, design-system
 * §2.3): in dark mode the page becomes `#1C1C1E`, cards `#2C2C2E`, raised boxes `#3A3A3C`. Light mode is unchanged.
 * `SignerBottomSheet` and `SignerAlertDialog` apply it; use it for any other window that floats above a page.
 */
@Composable
fun ElevatedSurfaceLevel(content: @Composable () -> Unit) {
    val colors = SignerTheme.colors.elevated()
    val scheme = remember(colors) { colors.toColorScheme() }
    CompositionLocalProvider(LocalSignerColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(LocalContentColor provides colors.label, content = content)
        }
    }
}

/** Accessors for the current theme, in the style of `MaterialTheme`. */
object SignerTheme {
    val colors: SignerColors
        @Composable @ReadOnlyComposable
        get() = LocalSignerColors.current

    val typography: SignerTypography
        @Composable @ReadOnlyComposable
        get() = LocalSignerTypography.current

    val shapes: SignerShapes get() = SignerShapes

    val radius: SignerRadius get() = SignerRadius

    val metrics: SignerMetrics get() = SignerMetrics

    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalSignerColors.current.isDark

    /** "Remove animations" is on (see [LocalReduceMotion]). */
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalReduceMotion.current

    /**
     * iOS `dynamicTypeSize.isAccessibilitySize` (AX1 ≈ body × 1.65): font scale ≥ 1.6. Two-line button labels, hidden
     * chain names, icon-only compact buttons, stacked `ProfileMenu` / `AddressIndexControl` rows.
     */
    val isAccessibilityTextSize: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalDensity.current.fontScale >= ACCESSIBILITY_FONT_SCALE

    /** iOS `dynamicTypeSize >= .xxLarge` (≈ × 1.24): font scale ≥ 1.25 (wider `WalletGroup` columns). */
    val isExtraLargeText: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalDensity.current.fontScale >= EXTRA_LARGE_FONT_SCALE

    const val ACCESSIBILITY_FONT_SCALE = 1.6f
    const val EXTRA_LARGE_FONT_SCALE = 1.25f
}

/**
 * The system's own dark-theme setting, for an "Appearance: System" choice. Not `isSystemInDarkTheme()` (the activity's
 * configuration): once `:app` applies the in-app theme as AppCompat's night mode, the activity configuration carries the
 * in-app choice, and a later switch back to System reaches the resources but not the views until the next
 * configuration change (Ownbit D18). The application configuration is the system's; its changes arrive as component
 * callbacks. Previews and screenshot tests keep their configuration.
 */
@Composable
fun rememberSystemInDarkTheme(): Boolean {
    val configurationDark = isSystemInDarkTheme()
    val view = LocalView.current
    if (view.isInEditMode) return configurationDark
    val app = LocalContext.current.applicationContext
    var dark by remember(app) { mutableStateOf(app.resources.configuration.isNight()) }
    DisposableEffect(app) {
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                dark = newConfig.isNight()
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        }
        app.registerComponentCallbacks(callbacks)
        dark = app.resources.configuration.isNight()
        onDispose { app.unregisterComponentCallbacks(callbacks) }
    }
    return dark
}

private fun Configuration.isNight(): Boolean = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** Whether the developer / accessibility "Remove animations" setting is on, observed while composed. */
@Composable
private fun rememberSystemReduceMotion(): Boolean {
    val context = LocalContext.current
    val view = LocalView.current
    if (view.isInEditMode) return false
    var reduce by remember(context) { mutableStateOf(readReduceMotion(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = readReduceMotion(context)
            }
        }
        runCatching {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    return reduce
}

internal fun readReduceMotion(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        .getOrDefault(false)

/** Status / navigation bar icon colours of the hosting activity window (never a dialog's). */
@Composable
private fun SystemBarAppearance(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = view.context.findActivity()?.window ?: return
    // Only the activity's own content view: a sheet or dialog window manages its own bars.
    if (view.rootView !== window.decorView) return
    SideEffect {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = darkIcons
        controller.isAppearanceLightNavigationBars = darkIcons
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
