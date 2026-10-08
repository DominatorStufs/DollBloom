package com.doll.bloom.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The default indication for the Android TV cut of the app.
 *
 * A remote has no pointer, so D-pad focus is the only "where am I" a viewer
 * gets — and Material's ripple reacts to press alone, which on a television
 * means moving through a list looks exactly like standing still. Compose
 * draws no default focus highlight of its own (the View-level one is
 * switched off under AndroidComposeView), so this factory replaces the
 * theme's indication on tvRelease builds (see [DollBloomTheme]) and every
 * `clickable` in the app picks it up through LocalIndication: one place,
 * whole app, no per-screen focus code.
 *
 * Focused items get a crisp petal-pink ring with a soft glow behind it;
 * pressed items get a light wash, which is also what a D-pad centre press
 * looks like for the moment between press and activation.
 */
object TvRemoteIndication : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TvRemoteIndicationNode(interactionSource)

    override fun hashCode(): Int = -4242

    override fun equals(other: Any?): Boolean = other === this
}

private class TvRemoteIndicationNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {

    private var focusCount = 0
    private var pressCount = 0

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focusCount++
                    is FocusInteraction.Unfocus -> focusCount--
                    is PressInteraction.Press -> pressCount++
                    is PressInteraction.Release -> pressCount--
                    is PressInteraction.Cancel -> pressCount--
                    else -> Unit
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val radius = CornerRadius(RING_RADIUS_DP.dp.toPx())
        when {
            focusCount > 0 -> {
                // Half of this centred stroke falls outside the bounds and is
                // clipped away; the surviving inner half is exactly the soft
                // edge a glow should have.
                drawRoundRect(
                    color = GLOW_COLOR,
                    cornerRadius = radius,
                    style = Stroke(width = GLOW_DP.dp.toPx() * 2),
                )
                val ring = RING_DP.dp.toPx()
                val inset = ring / 2 + 1f
                drawRoundRect(
                    color = RING_COLOR,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = radius,
                    style = Stroke(width = ring),
                )
            }

            pressCount > 0 -> drawRoundRect(color = PRESS_COLOR, cornerRadius = radius)
        }
    }

    private companion object {
        const val RING_RADIUS_DP = 16
        const val RING_DP = 3
        const val GLOW_DP = 3
        val RING_COLOR = Color(0xFFFFD3E4)
        val GLOW_COLOR = Color(0x55F06292)
        val PRESS_COLOR = Color(0x22FFFFFF)
    }
}
