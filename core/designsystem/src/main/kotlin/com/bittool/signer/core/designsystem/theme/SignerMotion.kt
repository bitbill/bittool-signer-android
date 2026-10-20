package com.bittool.signer.core.designsystem.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.PI

/**
 * The SwiftUI animation vocabulary as Compose springs (design-system.md §6.1, §9.11). SwiftUI's
 * `.smooth(duration:)` / `.snappy(duration:)` are springs with a perceptual duration and a bounce; the conversion is
 * `dampingRatio = 1 − bounce` and `stiffness = (2π / duration)²` with unit mass, so `.smooth(0.35)` becomes damping 1,
 * stiffness 322. Smooth has no bounce, snappy 0.15. Layout springs never bounce (DECISIONS §7).
 *
 * With "Remove animations" (`ANIMATOR_DURATION_SCALE == 0`, `SignerTheme.reduceMotion`) callers pass
 * `reduceMotion = true`: snappy loses its bounce, and decorative repeats (hero bounce, scan-glyph pulse, viewfinder
 * pulse) are skipped entirely by their components. Data-carrying animation (the QR player) keeps running.
 */
object SignerMotion {
    /** `.smooth(duration)`: critically damped. */
    fun <T> smooth(seconds: Double, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = 1f, stiffness = stiffnessFor(seconds), visibilityThreshold = visibilityThreshold)

    /** `.snappy(duration)`: bounce 0.15 (damping 0.85), none under reduce motion. */
    fun <T> snappy(seconds: Double, reduceMotion: Boolean = false, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(
            dampingRatio = if (reduceMotion) 1f else SNAPPY_DAMPING,
            stiffness = stiffnessFor(seconds),
            visibilityThreshold = visibilityThreshold,
        )

    /** SwiftUI spring stiffness for a perceptual [seconds] duration: `(2π / seconds)²`. */
    fun stiffnessFor(seconds: Double): Float {
        require(seconds > 0) { "duration must be positive" }
        val omega = 2 * PI / seconds
        return (omega * omega).toFloat()
    }

    /** Damping ratio of SwiftUI `.snappy` (bounce 0.15). */
    const val SNAPPY_DAMPING = 0.85f

    /** Root stage cross-fade (loading → welcome → main), `.smooth(0.35)`. */
    fun <T> stage(): SpringSpec<T> = smooth(0.35)

    /** Scan tab Home → Review → Signature swap, `.smooth(0.3)`. */
    fun <T> contentSwap(): SpringSpec<T> = smooth(0.3)

    /** Secret reveal, onboarding paging, `.smooth(0.25)`. */
    fun <T> reveal(): SpringSpec<T> = smooth(0.25)

    /** Strength bar, QR section appearance, scanner panel, `.smooth(0.2)`. */
    fun <T> quick(): SpringSpec<T> = smooth(0.2)

    /** Toast in / out, `.spring(duration: 0.3)`. */
    fun <T> toast(): SpringSpec<T> = smooth(0.3)

    /** Welcome hero entrance, `.spring(duration: 0.8)`. */
    fun <T> hero(): SpringSpec<T> = smooth(0.8)

    /** Payload disclosure, pairing level, multisig picker, `.snappy(0.25)`. */
    fun <T> disclosure(reduceMotion: Boolean = false): SpringSpec<T> = snappy(0.25, reduceMotion)

    /** Chain picker selection slide, `.snappy(0.28)`. */
    fun <T> chainPicker(reduceMotion: Boolean = false): SpringSpec<T> = snappy(0.28, reduceMotion)

    /** Segmented controls (Compatibility tabs), `.snappy(0.2)`. */
    fun <T> segment(reduceMotion: Boolean = false): SpringSpec<T> = snappy(0.2, reduceMotion)

    /** Pressable cards (`PressableCardStyle`), `.snappy(0.18)`. */
    fun <T> press(reduceMotion: Boolean = false): SpringSpec<T> = snappy(0.18, reduceMotion)

    /** Risk acknowledgement checkbox, `.snappy(0.15)`. */
    fun <T> checkbox(reduceMotion: Boolean = false): SpringSpec<T> = snappy(0.15, reduceMotion)

    /** Viewfinder bracket pulse, `.easeInOut(1.2)` repeat-autoreverse. Skip under reduce motion. */
    fun <T> pulse(): InfiniteRepeatableSpec<T> =
        infiniteRepeatable(tween(durationMillis = 1_200, easing = FastOutSlowInEasing), RepeatMode.Reverse)

    /** Symbol `.contentTransition(.symbolEffect(.replace))`: a quick scale + fade, about 150 ms. */
    const val SYMBOL_REPLACE_MILLIS = 150

    /** One-shot symbol `.bounce`: 1 → 1.12 → 1 in about 350 ms. */
    const val SYMBOL_BOUNCE_MILLIS = 350
    const val SYMBOL_BOUNCE_SCALE = 1.12f

    /** `StatusToast` stays this long once shown (`Design/DesignSystem.swift:320`). */
    const val TOAST_MILLIS = 1_600L

    /** Copy buttons show their checkmark this long (`Design/AddressText.swift:104`). */
    const val COPIED_MILLIS = 1_500L
}
