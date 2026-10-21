package com.bittool.signer.feature.wallets.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * Makes a plain element (a label next to a small clickable) the target of the touches that land on it (QA regression-09).
 *
 * Compose gives a clickable smaller than the minimum touch target (48 dp) the touches around it as well, but only those
 * that hit no other pointer target. The wallet page's fingerprint line is 4 dp from the name above it and from the
 * "Added …" date below it. Its 48 dp target therefore covered most of the date and the lower half of the name, and a
 * tap on the date copied the fingerprint. iOS copies only from the fingerprint (`WalletDetailView.swift:141-153`).
 *
 * A touch on an element with this modifier hits that element, so the hit test does not give it to the neighbour. The
 * element reacts to nothing and consumes nothing: the touch still reaches its ancestors, so a drag that starts on it
 * still scrolls the page. It adds no semantics. The neighbour keeps the rest of its expanded target, including its
 * 48 dp bounds for accessibility services.
 */
internal fun Modifier.ownsItsTouches(): Modifier = this then OwnsItsTouchesElement

private data object OwnsItsTouchesElement : ModifierNodeElement<OwnsItsTouchesNode>() {
    override fun create() = OwnsItsTouchesNode()

    override fun update(node: OwnsItsTouchesNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "ownsItsTouches"
    }
}

private class OwnsItsTouchesNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit

    override fun onCancelPointerInput() = Unit
}
