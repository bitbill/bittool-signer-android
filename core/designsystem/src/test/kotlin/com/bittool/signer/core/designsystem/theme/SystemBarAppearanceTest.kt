package com.bittool.signer.core.designsystem.theme

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.view.WindowCompat
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The bar icon colours SignerTheme gives the activity window (design-system §9.2, Ownbit `OwnbitTheme`). */
@RunWith(RobolectricTestRunner::class)
class SystemBarAppearanceTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * A nested always-dark theme (the scanner subtree) turns the icons light while it is shown and hands the light
     * page its dark icons back when it leaves, instead of leaving white icons on a white status bar.
     */
    @Test
    fun aNestedDarkThemeGivesTheBarsBackWhenItLeaves() {
        var scanner by mutableStateOf(false)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false) {
                if (scanner) SignerTheme(darkTheme = true) {}
            }
        }
        fun lightBars(): Boolean = compose.runOnIdle {
            val activity = view.context as android.app.Activity
            WindowCompat.getInsetsController(activity.window, view).isAppearanceLightStatusBars
        }
        assertTrue(lightBars(), "dark icons on the light page")
        scanner = true
        assertFalse(lightBars(), "light icons over the dark subtree")
        scanner = false
        assertTrue(lightBars(), "dark icons again once it is gone")
    }
}
