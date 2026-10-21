package com.bittool.signer.core.designsystem.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.bittool.signer.core.designsystem.theme.PhraseBreaks
import com.bittool.signer.core.designsystem.theme.headingLineBreaks
import com.bittool.signer.core.designsystem.theme.phraseBreakable
import com.bittool.signer.core.designsystem.theme.phraseLineBreaks

/**
 * A Material [Text] whose Japanese and Korean lines break between phrases on every Android version (QA setup-15,
 * regression-13, backlog B31): titles in a [headingLineBreaks] style, running text in a [phraseLineBreaks] style. From
 * API 33 (Korean: 35) the platform keeps the phrases together by itself and [text] is drawn as written; below, the drawn
 * copy carries word joiners ([phraseBreakable], [PhraseBreaks]). TalkBack, the accessibility tree and the e2e suite
 * always read [text] exactly as written: the drawn copy only replaces the node's text, so its other semantics (a
 * heading, a test tag, the text layout) stay. English, Chinese, Arabic and every other language are drawn as written.
 * Not for selectable text: inside a `SelectionContainer` a copy would carry the joiners. [onTextLayout] and the node's
 * text layout are the drawn copy's (their offsets count the joiners).
 *
 * The parameters are [Text]'s; [color] unspecified takes the content colour, as [Text] does.
 */
@Composable
fun PhraseText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val drawn = remember(text) { phraseBreakable(text) }
    Text(
        text = drawn,
        // Applied after the text's own semantics (a node collects its semantics from the innermost modifier out), so
        // the written text replaces the drawn one and nothing else changes.
        modifier = if (drawn == text) modifier else modifier.semantics { this.text = AnnotatedString(text) },
        color = color,
        textAlign = textAlign,
        overflow = overflow,
        maxLines = maxLines,
        onTextLayout = onTextLayout,
        style = style,
    )
}
