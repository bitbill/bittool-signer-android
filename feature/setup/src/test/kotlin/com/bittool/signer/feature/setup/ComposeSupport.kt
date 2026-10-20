package com.bittool.signer.feature.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
