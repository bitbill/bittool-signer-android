package com.bittool.signer.core.ui.password

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.FirstLineIcon
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerColors
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.PasswordPolicy
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.ui.text.asString

/**
 * The live password checklist (iOS `PasswordRequirementsView`, `Design/PasswordViews.swift:168-217`; setup-backup §9.2):
 * a four-segment strength meter (`PasswordPolicy.strength`: 1 warning, 2 yellow, 3–4 success, unfilled `fill`,
 * animated with `.smooth(0.2)`, hidden from accessibility) over three requirements in footnote — "8 to 64 characters",
 * "3 of: uppercase, lowercase, number, symbol", "Passwords match" — each a filled green check when met or an empty
 * circle, read as one element with the state "Met" / "Not met".
 *
 * Android addition (setup-backup §9.2 suggestion): when the password contains a control or format character (which iOS
 * rejects silently while all three items look met), a fourth, error-only line shows the policy message "Control
 * characters are not allowed."
 *
 * Takes [CharSequence]s (`TextFieldState.text`) so no `String` copy of the secret is made.
 */
@Composable
fun PasswordRequirements(password: CharSequence, confirmation: CharSequence, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val evaluation = PasswordPolicy.evaluate(password)
    val strength = PasswordPolicy.strength(password)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(SEGMENTS) { index ->
                val color by animateColorAsState(
                    targetValue = if (index < strength) strengthColor(strength, colors) else colors.fill,
                    animationSpec = SignerMotion.quick(),
                    label = "strength$index",
                )
                Box(Modifier.weight(1f).height(4.dp).background(color, CircleShape))
            }
        }
        Requirement(stringResource(R.string.n_8_to_64_characters), met = evaluation.hasValidLength)
        Requirement(stringResource(R.string.n_3_of_uppercase_lowercase_number_symbol), met = evaluation.categories >= 3)
        Requirement(stringResource(R.string.passwords_match), met = PasswordPolicy.matches(password, confirmation))
        if (evaluation.containsControl) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FirstLineIcon(SignerIcons.ErrorFilled, colors.danger, SignerTheme.typography.footnote, size = 16.dp)
                Text(UiText.Ios("Control characters are not allowed.").asString(), style = SignerTheme.typography.footnote, color = colors.dangerOnTint)
            }
        }
    }
}

/** The meter colour of a non-zero [strength] (iOS: 1 orange, 2 `systemYellow`, 3–4 green). */
internal fun strengthColor(strength: Int, colors: SignerColors): Color = when (strength) {
    1 -> colors.warning
    2 -> colors.strengthMid
    else -> colors.success
}

@Composable
private fun Requirement(text: String, met: Boolean) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val state = stringResource(if (met) R.string.met else R.string.not_met)
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = state },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AnimatedContent(
            targetState = met,
            transitionSpec = {
                (scaleIn(tween(SignerMotion.SYMBOL_REPLACE_MILLIS), 0.6f) + fadeIn(tween(SignerMotion.SYMBOL_REPLACE_MILLIS))) togetherWith
                    (scaleOut(tween(SignerMotion.SYMBOL_REPLACE_MILLIS), 0.6f) + fadeOut(tween(SignerMotion.SYMBOL_REPLACE_MILLIS)))
            },
            label = "requirement",
        ) { isMet ->
            FirstLineIcon(
                icon = if (isMet) SignerIcons.CheckCircleFilled else SignerIcons.Circle,
                tint = if (isMet) colors.success else colors.secondaryLabel,
                style = typography.footnote,
                size = 16.dp,
            )
        }
        Text(text, style = typography.footnote, color = if (met) colors.label else colors.secondaryLabel)
    }
}

private const val SEGMENTS = 4
