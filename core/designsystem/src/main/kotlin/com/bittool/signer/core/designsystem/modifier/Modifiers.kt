package com.bittool.signer.core.designsystem.modifier

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.bittool.signer.core.designsystem.theme.LocalReduceMotion
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * iOS `.readableWidth()` (`Design/DesignSystem.swift:32-34`): fills the width up to [max] and centres the column on
 * wider windows (tablets, landscape, foldables). Variants: 520 (Welcome, onboarding, address sheet), 560 (signature
 * result), 440 (lock) — see `SignerMetrics`.
 */
fun Modifier.readableWidth(max: Dp = SignerMetrics.readableWidth): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()

/**
 * Press feedback of large tappable surfaces (iOS `PressableCardStyle`, `Features/Sign/ScanTab.swift:254-261`): scale
 * [scale] and opacity [alpha] while [interactionSource] reports a press, animated with `.snappy(0.18)`. Pass the same
 * interaction source to the `clickable`. Under reduce motion only the opacity changes.
 */
fun Modifier.pressScale(interactionSource: InteractionSource, scale: Float = 0.98f, alpha: Float = 0.9f): Modifier =
    this then PressScaleElement(interactionSource, scale, alpha)

private data class PressScaleElement(
    val interactionSource: InteractionSource,
    val scale: Float,
    val alpha: Float,
) : ModifierNodeElement<PressScaleNode>() {
    override fun create() = PressScaleNode(interactionSource, scale, alpha)

    override fun update(node: PressScaleNode) {
        node.update(interactionSource, scale, alpha)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "pressScale"
        properties["scale"] = scale
        properties["alpha"] = alpha
    }
}

private class PressScaleNode(
    private var interactionSource: InteractionSource,
    private var pressedScale: Float,
    private var pressedAlpha: Float,
) : Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {
    /** 0 = released, 1 = pressed. */
    private val progress = Animatable(0f)
    private var collector: Job? = null

    override fun onAttach() = collect()

    override fun onDetach() {
        collector = null
    }

    fun update(source: InteractionSource, scale: Float, alpha: Float) {
        pressedScale = scale
        pressedAlpha = alpha
        if (source != interactionSource) {
            interactionSource = source
            collector?.cancel()
            collect()
        }
        invalidatePlacement()
    }

    private fun collect() {
        collector = coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            interactionSource.interactions.collect { interaction: Interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                }
                val target = if (presses.isEmpty()) 0f else 1f
                launch { animate(progress, target) }
            }
        }
    }

    // The layer block below reads `progress.value`, so the layer redraws on every animation frame by itself.
    private suspend fun animate(animatable: Animatable<Float, AnimationVector1D>, target: Float) {
        val reduce = currentValueOf(LocalReduceMotion)
        animatable.animateTo(target, SignerMotion.press(reduceMotion = reduce))
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val p = progress.value
                val reduce = currentValueOf(LocalReduceMotion)
                val s = if (reduce) 1f else 1f + (pressedScale - 1f) * p
                scaleX = s
                scaleY = s
                this.alpha = 1f + (pressedAlpha - 1f) * p
            }
        }
    }
}
