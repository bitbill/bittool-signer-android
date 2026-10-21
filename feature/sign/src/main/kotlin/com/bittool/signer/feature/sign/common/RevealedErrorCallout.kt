package com.bittool.signer.feature.sign.common

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.ui.text.Callout

/**
 * The danger callout of an error that answers an action (an import that failed), shown verbatim like iOS
 * `Callout(verbatim:tone: .danger)`. Each time it appears or its [text] changes, the scrolling parents move it into
 * their visible area — above the keyboard in a sheet whose action is pinned over the IME, above the tab bar on a page —
 * and TalkBack reads it out (polite live region). Laid out below the fold it would look as if nothing had happened
 * (QA sign-06, sign-07).
 */
@Composable
internal fun RevealedErrorCallout(text: UiText, modifier: Modifier = Modifier) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(text) {
        // The callout is composed in this frame and placed at its end: wait for the next frame so it has bounds.
        withFrameNanos { }
        requester.bringIntoView()
    }
    Callout(
        text = text,
        tone = Tone.Danger,
        modifier = modifier
            .bringIntoViewRequester(requester)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
