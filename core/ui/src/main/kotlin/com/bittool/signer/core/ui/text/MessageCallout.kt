package com.bittool.signer.core.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.model.UiText

/**
 * [Callout] for a message that arrives as [UiText] — store, vault, scanner, export and engine errors and warnings (iOS
 * `Callout(verbatim: error, …)`): resolved in the current language and, by default, [verbatim], i.e. laid out in its
 * own direction (`contentDirection`) like every iOS text. Such a message may be engine English shown as it is
 * ([UiText.Verbatim]), a Kotlin-host engine message or another Android-only string not yet translated (English in every
 * language until the translation pass), or a catalogue translation; in a right-to-left interface the English ones keep
 * their full stop and brackets at their end, and a translated one, which starts with its own script, reads as before.
 * Every error callout uses this overload, so no engine message is forced into the interface direction (backlog B15).
 */
@Composable
fun Callout(text: UiText, modifier: Modifier = Modifier, title: String? = null, tone: Tone = Tone.Info, verbatim: Boolean = true) {
    Callout(text = text.asString(), modifier = modifier, title = title, tone = tone, verbatim = verbatim)
}
