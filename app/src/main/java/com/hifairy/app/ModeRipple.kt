package com.hifairy.app

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.hypot
import kotlin.math.max

/** Clips the actual Agent UI; the translucent Activity exposes Chat outside the ripple. */
internal fun Modifier.modeCircularReveal(progress: Float, origin: Offset, rim: Color): Modifier =
    drawWithContent {
        if (progress >= 1f) {
            drawContent()
            return@drawWithContent
        }
        val eye = Offset(origin.x.coerceIn(0f, size.width), origin.y.coerceIn(0f, size.height))
        val furthest = hypot(max(eye.x, size.width - eye.x), max(eye.y, size.height - eye.y))
        val radius = (furthest + 4f) * progress.coerceIn(0f, 1f)
        val clip = Path().apply { addOval(Rect(eye, radius)) }
        clipPath(clip) { this@drawWithContent.drawContent() }
        if (progress in 0.02f..0.98f) {
            drawCircle(rim.copy(alpha = .5f * (1f - progress * .5f)), radius, eye,
                style = Stroke(width = 3f))
        }
    }
