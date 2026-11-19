package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.prefs.CaptionStyle
import com.example.data.prefs.PipelineConfig
import com.example.ui.theme.LocalStudioColors
import java.util.Locale

/**
 * Live 9:16 vertical render composition preview showing the top "Wait for it"
 * overlay, lower-third auto-captions, and hardware output resolution.
 */
@Composable
fun VerticalPreviewCard(
    config: PipelineConfig,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Crisp 9:16 vertical mini-canvas
            Box(
                modifier = Modifier
                    .width(126.dp)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .border(
                        width = 1.5.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(studio.velocityCyan, studio.liveCrimson)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_studio_radar_1795092446707),
                    contentDescription = "9:16 video composition preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f))
                )

                // Top "Wait for it" Hook Overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 18.dp, start = 10.dp, end = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(studio.liveCrimson)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = config.overlayText.ifBlank { "Wait for it" }.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                // Center framed 16:9->9:16 crop window guide
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 8.dp)
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, studio.velocityCyan.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "LIVE VIDEO + AUDIO",
                        style = MaterialTheme.typography.labelSmall,
                        color = studio.velocityCyan
                    )
                }

                // Lower-third Spoken Auto-Captions Preview
                if (config.autoCaptionsEnabled) {
                    val captionBg = when (config.captionStyle) {
                        CaptionStyle.DYNAMIC_PILL -> MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
                        CaptionStyle.BROADCAST_BAR -> MaterialTheme.colorScheme.background.copy(alpha = 0.92f)
                        CaptionStyle.BOLD_OUTLINE -> MaterialTheme.colorScheme.background.copy(alpha = 0.25f)
                    }
                    val shape = if (config.captionStyle == CaptionStyle.BROADCAST_BAR) {
                        RoundedCornerShape(0.dp)
                    } else {
                        RoundedCornerShape(8.dp)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                bottom = 16.dp,
                                start = if (config.captionStyle == CaptionStyle.BROADCAST_BAR) 0.dp else 8.dp,
                                end = if (config.captionStyle == CaptionStyle.BROADCAST_BAR) 0.dp else 8.dp
                            )
                            .fillMaxWidth()
                            .clip(shape)
                            .background(captionBg)
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "SPOKEN CAPTIONS",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
                            color = studio.velocityCyan
                        )
                        Text(
                            text = "Pure live video • no chat",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 10.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "9:16 Auto-Master Spec",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                SpecLine("Resolution", "${config.videoProfile.width} × ${config.videoProfile.height}")
                SpecLine("Clip Window", "${config.minClipSeconds}s – ${config.maxClipSeconds}s")
                SpecLine("Spike Trigger", String.format(Locale.US, "%.1fx baseline", config.spikeMultiplier))
                SpecLine("Top Overlay", "\"${config.overlayText.ifBlank { "Wait for it" }}\"")
                SpecLine(
                    "Auto-Captions",
                    if (config.autoCaptionsEnabled) config.captionStyle.displayName else "Disabled"
                )
                SpecLine("Output Target", "≥ ${config.targetClipsPerFiveMin} clips / 5m")
            }
        }
    }
}

@Composable
private fun SpecLine(label: String, value: String) {
    val studio = LocalStudioColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = studio.mutedText
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
