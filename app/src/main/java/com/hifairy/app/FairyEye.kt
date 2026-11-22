package com.hifairy.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.math.sin

private data class EyeGlitchBand(val top: Float, val height: Float, val brightness: Float)
private object EyeGazeMotion {
    const val maxTravel = 11f
    const val gravity = 9.81f
    const val sensorSmoothing = .22f
    const val motionThreshold = .4f
}

/** Calibrate against the phone's pose when the eye appears; listen only while visible and idle. */
@Composable
private fun rememberPhoneGaze(enabled: Boolean): Offset {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var gaze by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(context, lifecycle, enabled) {
        if (!enabled) {
            gaze = Offset.Zero
            onDispose { }
        } else {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            var baseline: Offset? = null
            var filtered = Offset.Zero
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val value = Offset(event.values[0], event.values[1])
                    val origin = baseline ?: value.also { baseline = it }
                    val delta = value - origin
                    val target = Offset(
                        (delta.x / EyeGazeMotion.gravity * EyeGazeMotion.maxTravel).coerceIn(-EyeGazeMotion.maxTravel, EyeGazeMotion.maxTravel),
                        (-delta.y / EyeGazeMotion.gravity * EyeGazeMotion.maxTravel).coerceIn(-EyeGazeMotion.maxTravel, EyeGazeMotion.maxTravel)
                    )
                    filtered += (target - filtered) * EyeGazeMotion.sensorSmoothing
                    if ((filtered - gaze).getDistance() >= EyeGazeMotion.motionThreshold) gaze = filtered
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            fun register() {
                baseline = null
                filtered = Offset.Zero
                gaze = Offset.Zero
                if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> register()
                    Lifecycle.Event.ON_PAUSE -> manager.unregisterListener(listener)
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
            onDispose {
                manager.unregisterListener(listener)
                lifecycle.removeObserver(observer)
            }
        }
    }
    return gaze
}

/** Fairy-DSH eye geometry and timing, adapted from the local Apache-2.0 reference. */
@Composable
internal fun FairyEye(modifier: Modifier = Modifier, active: Boolean = false, completionToken: Int = 0, blinkToken: Int = 0) {
    val phoneGaze = rememberPhoneGaze(!active)
    val motion = rememberInfiniteTransition(label = "Fairy eye")
    val breath by motion.animateFloat(1f, .92f, infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse), label = "iris breathing")
    val halo by motion.animateFloat(.55f, .95f, infiniteRepeatable(tween(2300, easing = EaseInOutSine), RepeatMode.Reverse), label = "halo")
    val ring by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "pulse ring")
    val cornerRotation by motion.animateFloat(0f, 360f,
        infiniteRepeatable(tween(if (active) 1900 else 12000, easing = LinearEasing)), label = "rotating corners")
    val scan by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "working scan")
    val completion = remember { Animatable(1f) }
    LaunchedEffect(completionToken) {
        if (completionToken > 0) {
            completion.snapTo(0f)
            completion.animateTo(1f, tween(950, easing = FastOutSlowInEasing))
        }
    }
    val focus by animateFloatAsState(if (active) .54f else 0f, tween(340), label = "thinking eyelid")
    var blink by remember { mutableStateOf(false) }
    var tappedBlink by remember { mutableStateOf(false) }
    LaunchedEffect(blinkToken) {
        if (blinkToken > 0) {
            tappedBlink = true
            delay(150)
            tappedBlink = false
        }
    }
    var glitchBands by remember { mutableStateOf(emptyList<EyeGlitchBand>()) }
    LaunchedEffect(active) {
        glitchBands = emptyList()
        delay(180)
        while (true) {
            glitchBands = List(Random.nextInt(2, 4)) {
                EyeGlitchBand(Random.nextFloat() * .82f, .025f + Random.nextFloat() * .06f, .12f + Random.nextFloat() * .15f)
            }
            delay(Random.nextLong(150, 290))
            glitchBands = emptyList()
            delay(if (active) Random.nextLong(4200, 9400) else Random.nextLong(7600, 17000))
        }
    }
    LaunchedEffect(active) {
        blink = false
        if (!active) while (true) {
            delay(Random.nextLong(3400, 8600))
            blink = true
            delay(300)
            blink = false
            if (Random.nextFloat() < .24f) {
                delay(170); blink = true; delay(260); blink = false
            }
        }
    }
    val lid by animateFloatAsState(if (blink || tappedBlink) 1f else focus, tween(if (blink || tappedBlink) 135 else 420, easing = FastOutSlowInEasing), label = "blink")
    val gazeX by animateFloatAsState(phoneGaze.x, tween(160, easing = FastOutSlowInEasing), label = "phone gaze x")
    val gazeY by animateFloatAsState(phoneGaze.y, tween(160, easing = FastOutSlowInEasing), label = "phone gaze y")
    val celebrationScale = if (completion.value < 1f) 1f + .13f * sin(completion.value * Math.PI).toFloat() else 1f
    Canvas(modifier.graphicsLayer { scaleX = celebrationScale; scaleY = celebrationScale }) {
        val unit = size.minDimension / 160f
        val center = Offset(size.width / 2f, size.height / 2f)
        fun circle(color: Color, radius: Float, at: Offset = center) = drawCircle(color, radius * unit, at)
        circle(Color(0xFF3454CE).copy(alpha = .16f * halo), 79f)
        circle(Color(0xFFBCEEFF).copy(alpha = .22f * halo), 72f)
        drawCircle(Color(0xFFDCEAFF), 68.8f * unit, center, style = Stroke(1.8f * unit))
        drawCircle(Brush.linearGradient(listOf(Color(0xFF3145D6), Color(0xFF2233B2), Color(0xFF2B3CBC))), 68f * unit, center)
        drawCircle(Color(0xFFF2FBFF), 68f * unit, center, style = Stroke(1.2f * unit))
        rotate(cornerRotation, center) {
            circle(Color(0xFF1F2573), 52f)
            rotate(3f, center) {
                drawRoundRect(Color(0xFF1F2573), Offset(center.x - 43f * unit, center.y - 43f * unit), androidx.compose.ui.geometry.Size(86f * unit, 86f * unit), androidx.compose.ui.geometry.CornerRadius(2f * unit))
            }
        }
        circle(Color(0xFFEEF0F5), 48f)
        circle(Color.White.copy(alpha = .26f), 48f, center)
        circle(Color(0xFFEEF0F5), 47f)
        translate(gazeX * unit, gazeY * unit) {
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
            if (active) {
                val scanY = center.y - 56f * unit + scan * 112f * unit
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xFFCDEFFF).copy(alpha = .42f), Color.Transparent),
                        startY = scanY - 12f * unit,
                        endY = scanY + 12f * unit
                    ),
                    topLeft = Offset(center.x - 56f * unit, scanY - 12f * unit),
                    size = androidx.compose.ui.geometry.Size(112f * unit, 24f * unit)
                )
            }
            val edge = center.y - (55f - 92f * lid) * unit
            val cover = Path().apply {
                moveTo(center.x - 60f * unit, center.y - 120f * unit)
                lineTo(center.x + 60f * unit, center.y - 120f * unit)
                lineTo(center.x + 60f * unit, edge)
                quadraticTo(center.x, edge + 30f * unit, center.x - 60f * unit, edge)
                close()
            }
            drawPath(cover, Color(0xFF1F2573))
            glitchBands.forEach { band ->
                val y = center.y - 56f * unit + band.top * 112f * unit
                drawRect(Color(0xFFB4E4FF).copy(alpha = band.brightness),
                    topLeft = Offset(center.x - 56f * unit, y),
                    size = androidx.compose.ui.geometry.Size(112f * unit, band.height * 112f * unit))
                drawLine(Color(0xFFE3F8FF).copy(alpha = band.brightness * 1.8f),
                    Offset(center.x - 46f * unit, y), Offset(center.x + 46f * unit, y), .6f * unit)
            }
        }
        val ringPhase = ring.coerceIn(0f, .36f) / .36f
        if (ring < .36f) drawCircle(Color(0xFFC9F8FF).copy(alpha = (.7f * (1f - ringPhase))), (68f + 82f * ringPhase) * unit, center, style = Stroke(2f * unit))
        if (completion.value < 1f) {
            drawCircle(Color(0xFFBEE8FF).copy(alpha = .8f * (1f - completion.value)), (68f + 22f * completion.value) * unit, center, style = Stroke(2f * unit))
        }
    }
}
