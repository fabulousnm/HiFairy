package com.hifairy.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.hypot
import kotlin.math.max

/** Covers the outgoing screen before the Activity handoff; origin is measured from the eye. */
@Composable
internal fun ModeRipple(trigger: Int, origin: Offset, destination: Color, onCovered: () -> Unit) {
    val expansion = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            expansion.snapTo(0f)
            expansion.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
            onCovered()
        }
    }
    if (trigger > 0) Canvas(Modifier.fillMaxSize()) {
        val eye = Offset(origin.x.coerceIn(0f, size.width), origin.y.coerceIn(0f, size.height))
        val radius = hypot(max(eye.x, size.width - eye.x), max(eye.y, size.height - eye.y)) * expansion.value
        drawCircle(destination, radius, eye)
        if (expansion.value in 0.02f..0.99f) {
            drawCircle(Color.White.copy(alpha = .28f * (1f - expansion.value)), radius, eye,
                style = Stroke(width = 3f + 9f * (1f - expansion.value)))
        }
    }
}
