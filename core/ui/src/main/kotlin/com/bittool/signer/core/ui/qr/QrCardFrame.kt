package com.bittool.signer.core.ui.qr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme

/**
 * The plate a QR code is shown on (iOS `QRCodeView`'s plate, `Design/QRCodeView.swift:56-74`; design-system §5.12):
 * white in both themes with black modules, radius 24, a soft shadow (black 8 %, blur 16, y 6), an 18 dp inset, square
 * and at most [maxSide] wide, centred. The content is composed in the light theme (iOS forces `.colorScheme(.light)`)
 * so anything drawn on the plate — the code from `:core:scanner`'s renderer, a concealed recovery-QR placeholder, a
 * spinner — reads correctly on white. Callers' sizes: 280 (address sheet), 300 (Coin Level), 320 (Activity),
 * 340 (signature), 360 (Wallet Level).
 *
 * This is only the frame: encoding and the animated player live in `:core:scanner` (`QrCodeView`), so screens can
 * assemble QR cards without `:core:ui` depending on scanner symbols.
 */
@Composable
fun QrCardFrame(modifier: Modifier = Modifier, maxSide: Dp = 320.dp, content: @Composable BoxScope.() -> Unit) {
    val plate = SignerTheme.colors.qrPlate
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .widthIn(max = maxSide)
                .fillMaxWidth()
                .aspectRatio(1f)
                .dropShadow(SignerShapes.qr, Shadow(radius = 16.dp, color = Color.Black.copy(alpha = 0.08f), offset = DpOffset(0.dp, 6.dp)))
                .background(plate, SignerShapes.qr)
                .padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center, content = content)
            }
        }
    }
}

/**
 * The placeholder shown while a QR is being prepared (iOS `ConnectView.swift:233-238`): a card-coloured square, radius
 * 24, at most [maxSide] wide, with a spinner announced as in progress.
 */
@Composable
fun QrLoadingPlaceholder(modifier: Modifier = Modifier, maxSide: Dp = 300.dp) {
    val colors = SignerTheme.colors
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .widthIn(max = maxSide)
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(colors.card, SignerShapes.qr)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = colors.secondaryLabel, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
    }
}
