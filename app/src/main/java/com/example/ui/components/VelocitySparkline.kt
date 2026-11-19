package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.engine.VelocitySample
import com.example.ui.theme.LocalStudioColors
import kotlin.math.max

/**
 * Real-time live chat velocity sparkline showing message rate against
 * the baseline and the dashed 3x spike trigger line.
 */
@Composable
fun VelocitySparkline(
    samples: List<VelocitySample>,
    spikeMultiplier: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val signalColor = if (isRecording) studio.liveCrimson else studio.velocityCyan
    val thresholdColor = studio.thresholdAmber

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
    ) {
        val w = size.width
        val h = size.height
        val padY = 6.dp.toPx()
        val usableH = (h - padY * 2f).coerceAtLeast(1f)

        // Subtle horizontal studio grid lines
        for (i in 0..2) {
            val y = padY + (usableH * i / 2f)
            drawLine(
                color = gridColor.copy(alpha = 0.55f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        if (samples.isEmpty()) {
            // Flat standby line when awaiting first live chat batch
            val midY = h * 0.72f
            drawLine(
                color = signalColor.copy(alpha = 0.35f),
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )
            return@Canvas
        }

        val latestBaseline = samples.last().baselinePerMin.coerceAtLeast(2f)
        val triggerLevel = latestBaseline * spikeMultiplier
        val peakValue = max(
            samples.maxOf { it.velocityPerMin },
            triggerLevel * 1.15f
        ).coerceAtLeast(10f)

        fun yFor(value: Float): Float {
            val ratio = (value / peakValue).coerceIn(0f, 1f)
            return padY + usableH * (1f - ratio)
        }

        // Dashed 3x Trigger Threshold Line
        val thresholdY = yFor(triggerLevel)
        drawLine(
            color = thresholdColor.copy(alpha = 0.85f),
            start = Offset(0f, thresholdY),
            end = Offset(w, thresholdY),
            strokeWidth = 1.25.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
        )

        // Build waveform stroke and gradient fill paths
        val count = samples.size
        val stepX = if (count > 1) w / (count - 1).toFloat() else w
        val linePath = Path()
        val fillPath = Path()

        samples.forEachIndexed { index, sample ->
            val x = if (count > 1) index * stepX else w
            val y = yFor(sample.velocityPerMin)
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (index == count - 1) {
                fillPath.lineTo(x, h)
                fillPath.close()
            }
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    signalColor.copy(alpha = 0.30f),
                    signalColor.copy(alpha = 0.02f)
                )
            )
        )

        drawPath(
            path = linePath,
            color = signalColor,
            style = Stroke(
                width = 2.2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Active tip indicator
        val lastY = yFor(samples.last().velocityPerMin)
        val lastX = if (count > 1) w else w
        drawCircle(
            color = signalColor.copy(alpha = 0.25f),
            radius = 7.dp.toPx(),
            center = Offset(lastX - 3.dp.toPx(), lastY)
        )
        drawCircle(
            color = signalColor,
            radius = 3.5.dp.toPx(),
            center = Offset(lastX - 3.dp.toPx(), lastY)
        )
    }
}
