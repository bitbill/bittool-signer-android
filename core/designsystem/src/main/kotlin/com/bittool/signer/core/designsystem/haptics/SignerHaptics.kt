package com.bittool.signer.core.designsystem.haptics

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The four kinds of haptic feedback the app plays, mirroring iOS `.sensoryFeedback` (design-system.md §6.2, §9.11;
 * ARCHITECTURE §4.5). There is no in-app haptics switch: the system touch-feedback setting governs.
 *
 * - [Success]: a signature is created, a wallet is created, an address is copied, a status toast appears.
 * - [Selection]: segmented controls, the chain picker, the risk acknowledgement, a verification chip placed.
 * - [Warning] / [Reject]: a scanner error, a wrong password, a rejected verification.
 *
 * Nothing on plain navigation or ordinary button taps.
 */
enum class HapticKind { Success, Selection, Warning, Reject }

/**
 * The `HapticFeedbackConstants` value for [kind] on [sdk] (pure, unit-tested): `CONFIRM` / `REJECT` exist from API 30
 * and `SEGMENT_TICK` from API 34; older devices get the closest pre-existing constant.
 */
fun hapticConstantFor(kind: HapticKind, sdk: Int = Build.VERSION.SDK_INT): Int = when (kind) {
    HapticKind.Success -> if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    HapticKind.Selection -> if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    HapticKind.Warning, HapticKind.Reject ->
        if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
}

/**
 * Plays haptics through the hosting view (`View.performHapticFeedback` honours the system setting and needs no
 * permission). Obtain with [rememberSignerHaptics]; safe to call from click handlers and effects on the main thread.
 */
@Stable
class SignerHaptics internal constructor(private val view: View) {
    fun success() = perform(HapticKind.Success)
    fun selection() = perform(HapticKind.Selection)
    fun warning() = perform(HapticKind.Warning)
    fun reject() = perform(HapticKind.Reject)

    fun perform(kind: HapticKind) {
        view.performHapticFeedback(hapticConstantFor(kind))
    }
}

/** The [SignerHaptics] of the current view. */
@Composable
fun rememberSignerHaptics(): SignerHaptics {
    val view = LocalView.current
    return remember(view) { SignerHaptics(view) }
}
