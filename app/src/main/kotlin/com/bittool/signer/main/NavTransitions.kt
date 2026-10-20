package com.bittool.signer.main

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import com.bittool.signer.core.designsystem.theme.SignerMotion

/**
 * The navigation motion (ARCHITECTURE §4.6; design-system.md §6.1): shared-axis X for pushed pages (and their
 * predictive back, which scrubs the pop transitions), fade-through between tabs, a vertical slide for the modal routes
 * (Change Password, Delete, Repair, Recovery Phrase, the scanner: iOS sheets and covers). Mirrored in right-to-left
 * layouts. "Remove animations" scales every duration to zero (Compose follows the system animator scale).
 */
class NavTransitions(private val rtl: Boolean) {
    private enum class Kind { Tab, Modal, Push }

    private fun kind(from: NavBackStackEntry, to: NavBackStackEntry, pop: Boolean): Kind {
        val fromTab = from.destination.tab()
        val toTab = to.destination.tab()
        return when {
            fromTab != null && toTab != null && fromTab != toTab -> Kind.Tab
            (if (pop) from.destination else to.destination).isModal() -> Kind.Modal
            else -> Kind.Push
        }
    }

    /** +1 when "forward" moves content towards the start edge (left in LTR). */
    private val direction get() = if (rtl) -1 else 1

    fun enter(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition =
        when (kind(scope.initialState, scope.targetState, pop = false)) {
            Kind.Tab -> fadeThroughIn()
            Kind.Modal -> slideInVertically(SignerMotion.smooth<IntOffset>(MODAL_SECONDS)) { it }
            Kind.Push -> slideInHorizontally(axisSpec()) { width -> direction * axisOffset(width) } + axisFadeIn()
        }

    fun exit(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition =
        when (kind(scope.initialState, scope.targetState, pop = false)) {
            Kind.Tab -> fadeThroughOut()
            // The page stays visible under the rising page (kept for the whole transition at almost full opacity).
            Kind.Modal -> fadeOut(tween(MODAL_MILLIS), targetAlpha = 0.92f)
            Kind.Push -> slideOutHorizontally(axisSpec()) { width -> -direction * axisOffset(width) } + axisFadeOut()
        }

    fun popEnter(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition =
        when (kind(scope.initialState, scope.targetState, pop = true)) {
            Kind.Tab -> fadeThroughIn()
            Kind.Modal -> fadeIn(tween(MODAL_MILLIS), initialAlpha = 0.92f)
            Kind.Push -> slideInHorizontally(axisSpec()) { width -> -direction * axisOffset(width) } + axisFadeIn()
        }

    fun popExit(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition =
        when (kind(scope.initialState, scope.targetState, pop = true)) {
            Kind.Tab -> fadeThroughOut()
            Kind.Modal -> slideOutVertically(SignerMotion.smooth<IntOffset>(MODAL_SECONDS)) { it }
            Kind.Push -> slideOutHorizontally(axisSpec()) { width -> direction * axisOffset(width) } + axisFadeOut()
        }

    private fun axisSpec() = tween<IntOffset>(AXIS_MILLIS, easing = FastOutSlowInEasing)

    private fun axisOffset(width: Int) = (width * AXIS_FRACTION).toInt()

    private fun axisFadeIn() = fadeIn(tween(AXIS_MILLIS - OUT_MILLIS, delayMillis = OUT_MILLIS, easing = LinearOutSlowInEasing))

    private fun axisFadeOut() = fadeOut(tween(OUT_MILLIS, easing = FastOutSlowInEasing))

    private fun fadeThroughIn() =
        fadeIn(tween(THROUGH_IN_MILLIS, delayMillis = OUT_MILLIS, easing = LinearOutSlowInEasing)) +
            scaleIn(tween(THROUGH_IN_MILLIS, delayMillis = OUT_MILLIS, easing = LinearOutSlowInEasing), initialScale = 0.96f)

    private fun fadeThroughOut() = fadeOut(tween(OUT_MILLIS, easing = FastOutSlowInEasing))

    private companion object {
        /** Material shared axis: 300 ms, the outgoing page fades in the first 90 ms. */
        const val AXIS_MILLIS = 300
        const val OUT_MILLIS = 90
        const val THROUGH_IN_MILLIS = 210

        /** How far a page travels along the axis (a fraction of the width). */
        const val AXIS_FRACTION = 0.12f

        /** A modal page rises like an iOS sheet, without a bounce. */
        const val MODAL_SECONDS = 0.4
        const val MODAL_MILLIS = 400
    }
}
