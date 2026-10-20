package com.bittool.signer.core.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.monospacedDigit
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AccountProfiles
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The text ⇄ index rules of [AddressIndexControl] (iOS `ConnectView.swift:520-531`, wallets-pairing §3.8; pure,
 * unit-tested): an edit keeps only ASCII digits, the first 10; a number above [MAX] is rewritten as [MAX]; an empty
 * field leaves the index unchanged.
 */
object AddressIndexInput {
    /** Largest non-hardened BIP32 index, 2³¹ − 1. */
    const val MAX: Long = AccountProfiles.MAX_TRON_INDEX
    private const val MAX_DIGITS = 10

    /** What the field shows after an edit to [typed], and the index it selects (null: unchanged). */
    data class Edit(val text: String, val index: Long?)

    fun edit(typed: CharSequence): Edit {
        val digits = buildString { for (c in typed) if (c in '0'..'9' && length < MAX_DIGITS) append(c) }
        if (digits.isEmpty()) return Edit("", null)
        val number = digits.toLong()
        if (number > MAX) return Edit(MAX.toString(), MAX)
        return Edit(digits, number)
    }

    /** One stepper step from [index] by [delta], within 0…[MAX]. */
    fun step(index: Long, delta: Int): Long = (index + delta).coerceIn(0, MAX)
}

/**
 * The receive-index control of TRON pairing (iOS `AddressIndexControl`, `ConnectView.swift:474-545`): "Address index",
 * a centred number field (body, tabular digits, semibold, on a `tertiaryFill` capsule, 76 dp wide or 116 dp beyond five
 * digits, both scaled with the font) and a stepper of two buttons that repeat while held, then the path
 * `m/44'/195'/0'/0/<index>` (caption, monospaced, secondary; read as "Derivation path …"). Any index 0…2,147,483,647:
 * see [AddressIndexInput]. The field always shows the exported index — the stepper updates it while editing, and losing
 * focus rewrites it (`007` → `7`, empty → the index). At accessibility text sizes the label sits above the controls.
 * Tags: `connect.tronIndex.field`, `connect.tronIndex`.
 */
@Composable
fun AddressIndexControl(
    index: Long,
    onIndexChange: (Long) -> Unit,
    pathPrefix: String,
    modifier: Modifier = Modifier,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val stacked = SignerTheme.isAccessibilityTextSize
    val focusManager = LocalFocusManager.current
    val currentIndex by rememberUpdatedState(index)
    val currentOnChange by rememberUpdatedState(onIndexChange)
    val state = remember { TextFieldState(index.toString()) }
    // An edit applies its index at once (each applied change is a new pairing request, wallets-pairing §7.6).
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { typed ->
            val edit = AddressIndexInput.edit(typed)
            if (edit.text != typed) state.setTextAndPlaceCursorAtEnd(edit.text)
            val selected = edit.index
            if (selected != null && selected != currentIndex) currentOnChange(selected)
        }
    }
    // The stepper (or the caller) changed the index: the field shows it, also while editing (iOS onChange(index)).
    LaunchedEffect(index) {
        if (state.text.toString() != index.toString()) state.setTextAndPlaceCursorAtEnd(index.toString())
    }
    val fieldLabel = stringResource(R.string.address_index)
    val fontScale = LocalDensity.current.fontScale
    val fieldWidth = (if (state.text.length > 5) 116.dp else 76.dp) * fontScale
    val field: @Composable () -> Unit = {
        BasicTextField(
            state = state,
            modifier = Modifier
                .width(fieldWidth)
                .clip(SignerShapes.capsule)
                .background(colors.tertiaryFill)
                .padding(vertical = 6.dp, horizontal = 8.dp)
                .onFocusChanged { if (!it.isFocused) state.setTextAndPlaceCursorAtEnd(currentIndex.toString()) }
                .semantics { contentDescription = fieldLabel }
                .testTag("connect.tronIndex.field"),
            textStyle = typography.body.monospacedDigit().semibold().ltr().copy(color = colors.label, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            onKeyboardAction = KeyboardActionHandler { focusManager.clearFocus() },
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(colors.accent),
            decorator = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (state.text.isEmpty()) {
                        Text(stringResource(R.string.n_0), style = typography.body.monospacedDigit(), color = colors.tertiaryLabel, textAlign = TextAlign.Center)
                    }
                    inner()
                }
            },
        )
    }
    val stepper: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .clip(SignerShapes.capsule)
                .background(colors.tertiaryFill)
                .testTag("connect.tronIndex"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A step at a bound changes nothing and is not reported (each reported change is a new pairing request).
            RepeatingStepButton(SignerIcons.Remove, stringResource(R.string.ui_decrease), enabled = index > 0) {
                val next = AddressIndexInput.step(currentIndex, -1)
                if (next != currentIndex) currentOnChange(next)
            }
            Box(Modifier.size(width = 0.5.dp, height = 18.dp).background(colors.opaqueSeparator))
            RepeatingStepButton(SignerIcons.Add, stringResource(R.string.ui_increase), enabled = index < AddressIndexInput.MAX) {
                val next = AddressIndexInput.step(currentIndex, +1)
                if (next != currentIndex) currentOnChange(next)
            }
        }
    }
    val path = pathPrefix + index
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SignerShapes.control)
            .background(colors.card)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (stacked) {
            Text(fieldLabel, style = typography.subheadline, color = colors.label, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                field()
                stepper()
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fieldLabel, style = typography.subheadline, color = colors.label, maxLines = 1, modifier = Modifier.weight(1f))
                field()
                stepper()
            }
        }
        val pathLabel = stringResource(R.string.derivation_path_x, path)
        Text(
            text = path,
            style = typography.caption.monospaced().ltr(),
            color = colors.secondaryLabel,
            modifier = Modifier.semantics { contentDescription = pathLabel },
        )
    }
}

/**
 * One stepper button: a step on tap, then a step every 80 ms after 400 ms held (the iOS stepper auto-repeats). The
 * gesture outlives [enabled] turning false at a bound (the repeat just stops), so the pressed highlight always ends
 * with the finger, however the gesture ends.
 */
@Composable
private fun RepeatingStepButton(icon: ImageVector, label: String, enabled: Boolean, onStep: () -> Unit) {
    val colors = SignerTheme.colors
    val step by rememberUpdatedState(onStep)
    val canStep by rememberUpdatedState(enabled)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (pressed && enabled) colors.fill else Color.Transparent)
            .semantics {
                role = Role.Button
                contentDescription = label
                if (enabled) onClick { step(); true } else disabled()
            }
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!canStep) return@awaitEachGesture
                        down.consume()
                        pressed = true
                        try {
                            step()
                            val repeat = launch {
                                delay(REPEAT_DELAY_MILLIS)
                                while (isActive && canStep) {
                                    step()
                                    delay(REPEAT_INTERVAL_MILLIS)
                                }
                            }
                            waitForUpOrCancellation()?.consume()
                            repeat.cancel()
                        } finally {
                            pressed = false
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) colors.accent else colors.tertiaryLabel, modifier = Modifier.size(20.dp))
    }
}

private const val REPEAT_DELAY_MILLIS = 400L
private const val REPEAT_INTERVAL_MILLIS = 80L
