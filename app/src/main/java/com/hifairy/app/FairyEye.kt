package com.hifairy.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Fairy-DSH eye geometry and timing, adapted from the local Apache-2.0 reference. */
@Composable
internal fun FairyEye(modifier: Modifier = Modifier, active: Boolean = false) {
    val motion = rememberInfiniteTransition(label = "Fairy eye")
    val breath by motion.animateFloat(1f, .92f, infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse), label = "iris breathing")
    val halo by motion.animateFloat(.55f, .95f, infiniteRepeatable(tween(2300, easing = EaseInOutSine), RepeatMode.Reverse), label = "halo")
    val ring by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "pulse ring")
    val focus by animateFloatAsState(if (active) .54f else 0f, tween(340), label = "thinking eyelid")
    var blink by remember { mutableStateOf(false) }
    var glance by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        blink = false
        glance = 0f
        if (!active) while (true) {
            delay(Random.nextLong(3400, 8600))
            blink = true
            delay(300)
            blink = false
            if (Random.nextFloat() < .24f) {
                delay(170); blink = true; delay(260); blink = false
            }
            if (Random.nextFloat() < .45f) {
                glance = if (Random.nextBoolean()) -5f else 5f
                delay(720)
                glance = 0f
            }
        }
    }
    val lid by animateFloatAsState(if (blink) 1f else focus, tween(if (blink) 135 else 420, easing = FastOutSlowInEasing), label = "blink")
    val gaze by animateFloatAsState(glance, tween(360, easing = FastOutSlowInEasing), label = "glance")
    Canvas(modifier) {
        val unit = size.minDimension / 160f
        val center = Offset(size.width / 2f, size.height / 2f)
        fun circle(color: Color, radius: Float, at: Offset = center) = drawCircle(color, radius * unit, at)
        circle(Color(0xFF3454CE).copy(alpha = .16f * halo), 79f)
        circle(Color(0xFFBCEEFF).copy(alpha = .22f * halo), 72f)
        drawCircle(Color(0xFFDCEAFF), 68.8f * unit, center, style = Stroke(1.8f * unit))
        drawCircle(Brush.linearGradient(listOf(Color(0xFF3145D6), Color(0xFF2233B2), Color(0xFF2B3CBC))), 68f * unit, center)
        drawCircle(Color(0xFFF2FBFF), 68f * unit, center, style = Stroke(1.2f * unit))
        rotate(if (active) ring * 90f else 3f, center) {
            circle(Color(0xFF1F2573), 52f)
            drawRoundRect(Color(0xFF1F2573), Offset(center.x - 43f * unit, center.y - 43f * unit), androidx.compose.ui.geometry.Size(86f * unit, 86f * unit), androidx.compose.ui.geometry.CornerRadius(2f * unit))
        }
        circle(Color(0xFFEEF0F5), 48f)
        circle(Color.White.copy(alpha = .26f), 48f, center)
        circle(Color(0xFFEEF0F5), 47f)
        translate(gaze * unit, 0f) {
            scale(breath, breath, center) {
                circle(Color(0xFF9DAEE0), 33f)
                circle(Color(0xFFEEF0F5), 24.15f)
                circle(Color(0xFF2F74C9), 23.5f)
                circle(Color(0xFF2E2F78), 16f)
                circle(Color(0xFFF5F8FD).copy(alpha = .72f), 11f, center + Offset(18f * unit, 20.5f * unit))
            }
        }
        val eyeClip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, 56f * unit)) }
        clipPath(eyeClip) {
            val edge = center.y - (55f - 92f * lid) * unit
            val cover = Path().apply {
                moveTo(center.x - 60f * unit, center.y - 120f * unit)
                lineTo(center.x + 60f * unit, center.y - 120f * unit)
                lineTo(center.x + 60f * unit, edge)
                quadraticTo(center.x, edge + 30f * unit, center.x - 60f * unit, edge)
                close()
            }
            drawPath(cover, Color(0xFF1F2573))
        }
        val ringPhase = ring.coerceIn(0f, .36f) / .36f
        if (ring < .36f) drawCircle(Color(0xFFC9F8FF).copy(alpha = (.7f * (1f - ringPhase))), (68f + 82f * ringPhase) * unit, center, style = Stroke(2f * unit))
    }
}
