package com.bittool.signer.core.ui.theme

import androidx.compose.runtime.Composable
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.rememberSystemInDarkTheme
import com.bittool.signer.core.model.Appearance

/**
 * Whether [appearance] (Settings › Appearance › Theme: System / Light / Dark, design-system §2.7) is dark now. System
 * follows the system setting through the application configuration, so a switch back to System applies at once even
 * while AppCompat's night mode still carries the previous in-app choice.
 */
@Composable
fun Appearance.isDark(): Boolean = when (this) {
    Appearance.System -> rememberSystemInDarkTheme()
    Appearance.Light -> false
    Appearance.Dark -> true
}

/** [SignerTheme] for a stored [Appearance] (what `MainActivity` and the lock window use). */
@Composable
fun SignerTheme(appearance: Appearance, applySystemBarAppearance: Boolean = true, content: @Composable () -> Unit) {
    SignerTheme(darkTheme = appearance.isDark(), applySystemBarAppearance = applySystemBarAppearance, content = content)
}
