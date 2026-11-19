package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.prefs.CaptionStyle
import com.example.data.prefs.PipelineConfig
import com.example.data.prefs.VideoQualityProfile
import com.example.ui.PulseClipUiState
import com.example.ui.components.ActiveClipProgressBanner
import com.example.ui.components.VerticalPreviewCard
import com.example.ui.theme.LocalStudioColors
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PipelineScreen(
    uiState: PulseClipUiState,
    onUpdateConfig: ((PipelineConfig) -> PipelineConfig) -> Unit,
    onRequestScreenCapture: () -> Unit,
    onDetachScreenCapture: () -> Unit,
    onStopManualCut: (String) -> Unit,
    onCancelManualCut: (String) -> Unit,
    onOpenClip: (Long) -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current
    val config = uiState.config
    var overlayDraft by remember(config.overlayText) { mutableStateOf(config.overlayText) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = stringResource(R.string.pipeline_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.pipeline_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Live Progress Banner (Recording / Encoding / Saving)
        item(key = "pipeline_live_progress") {
            ActiveClipProgressBanner(
                activeRecording = uiState.activeRecording,
                queueTelemetry = uiState.queueTelemetry,
                activePipelineJobs = uiState.activePipelineJobs,
                onOpenClip = onOpenClip,
                onDismiss = onDismissBanner,
                manualClipCutEnabled = uiState.config.manualClipCutEnabled,
                onStopManualCut = onStopManualCut,
                onCancelManualCut = onCancelManualCut
            )
        }

        // Live 9:16 Composition Preview
        item(key = "preview") {
            VerticalPreviewCard(config = config)
        }

        // Active Encoding Queue
        if (uiState.activePipelineJobs.isNotEmpty()) {
            item(key = "queue_title") {
                Text(
                    text = "Active 9:16 Encoding Queue (${uiState.activePipelineJobs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = studio.velocityCyan
                )
            }
            items(uiState.activePipelineJobs, key = { it.id }) { job ->
                val stageText = if (uiState.queueTelemetry.activeClipId == job.id && uiState.queueTelemetry.activeStageLabel.isNotBlank()) {
                    uiState.queueTelemetry.activeStageLabel
                } else {
                    "${job.status} • Waiting for encoder…"
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, studio.velocityCyan.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${job.channelTitle} • ${job.durationSeconds}s",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${job.progressPercent}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = studio.velocityCyan
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stageText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (job.progressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = studio.velocityCyan,
                            trackColor = studio.velocityCyanContainer
                        )
                    }
                }
            }
        }

        // Optional MediaProjection Live Screen Video Bridge
        item(key = "screen_bridge") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(
                    1.dp,
                    if (uiState.isScreenCaptureActive) studio.targetEmerald else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Cast,
                            contentDescription = null,
                            tint = if (uiState.isScreenCaptureActive) studio.targetEmerald else studio.velocityCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.screen_capture_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "PulseClip automatically records live HLS video + audio + spoken captions directly in the background (without recording chat). If a broadcast is DRM-restricted by YouTube, attach the Screen + Internal Audio Bridge to record real 24fps video & crisp system playback audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.isScreenCaptureActive) {
                        OutlinedButton(
                            onClick = onDetachScreenCapture,
                            modifier = Modifier.testTag("detach_screen_capture_button"),
                            border = BorderStroke(1.dp, studio.liveCrimson)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.StopScreenShare,
                                contentDescription = null,
                                tint = studio.liveCrimson,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Detach Screen + Audio Bridge", color = studio.liveCrimson)
                        }
                    } else {
                        Button(
                            onClick = onRequestScreenCapture,
                            modifier = Modifier.testTag("attach_screen_capture_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = studio.elevatedCard,
                                contentColor = studio.velocityCyan
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Cast,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Attach Screen + Audio Bridge")
                        }
                    }
                }
            }
        }

        // Trigger & Clip Duration Controls
        item(key = "spike_controls") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.MovieCreation,
                            contentDescription = null,
                            tint = studio.liveCrimson,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Spike Detection & Clip Window",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    // Manual Clip Cut ON/OFF Toggle (synced with Monitor Control Deck)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCut,
                                contentDescription = null,
                                tint = if (config.manualClipCutEnabled) studio.velocityCyan else studio.mutedText,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.manual_cut_title),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(
                                        if (config.manualClipCutEnabled) R.string.manual_cut_subtitle_on
                                        else R.string.manual_cut_subtitle_off
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = config.manualClipCutEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateConfig { it.copy(manualClipCutEnabled = enabled) }
                            },
                            modifier = Modifier.testTag("pipeline_manual_cut_toggle"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = studio.velocityCyan
                            )
                        )
                    }

                    // Spike Multiplier
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Chat Velocity Spike Trigger", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = String.format(Locale.US, "%.1fx baseline", config.spikeMultiplier),
                            style = MaterialTheme.typography.labelLarge,
                            color = studio.thresholdAmber
                        )
                    }
                    Slider(
                        value = config.spikeMultiplier,
                        onValueChange = { v ->
                            val snapped = (v * 2f).roundToInt() / 2f
                            onUpdateConfig { it.copy(spikeMultiplier = snapped) }
                        },
                        valueRange = 2.0f..5.0f,
                        steps = 5,
                        modifier = Modifier.testTag("spike_multiplier_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = studio.thresholdAmber,
                            activeTrackColor = studio.thresholdAmber
                        )
                    )

                    // Min Clip Duration (30s - 45s)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Minimum Clip Duration", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${config.minClipSeconds}s",
                            style = MaterialTheme.typography.labelLarge,
                            color = studio.velocityCyan
                        )
                    }
                    Slider(
                        value = config.minClipSeconds.toFloat(),
                        onValueChange = { v ->
                            val sec = v.roundToInt().coerceIn(30, 45)
                            onUpdateConfig { it.copy(minClipSeconds = sec) }
                        },
                        valueRange = 30f..45f,
                        steps = 14,
                        colors = SliderDefaults.colors(
                            thumbColor = studio.velocityCyan,
                            activeTrackColor = studio.velocityCyan
                        )
                    )

                    // Max Clip Duration (45s - 60s)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Maximum Clip Duration", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${config.maxClipSeconds}s",
                            style = MaterialTheme.typography.labelLarge,
                            color = studio.velocityCyan
                        )
                    }
                    Slider(
                        value = config.maxClipSeconds.toFloat(),
                        onValueChange = { v ->
                            val sec = v.roundToInt().coerceIn(45, 60)
                            onUpdateConfig { it.copy(maxClipSeconds = sec) }
                        },
                        valueRange = 45f..60f,
                        steps = 14,
                        colors = SliderDefaults.colors(
                            thumbColor = studio.velocityCyan,
                            activeTrackColor = studio.velocityCyan
                        )
                    )
                }
            }
        }

        // Overlay & Auto-Caption Settings
        item(key = "overlay_captions") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.ClosedCaption,
                            contentDescription = null,
                            tint = studio.velocityCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Top Hook Overlay & Auto-Captions",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    OutlinedTextField(
                        value = overlayDraft,
                        onValueChange = { next ->
                            overlayDraft = next.take(36)
                            onUpdateConfig { it.copy(overlayText = overlayDraft.ifBlank { "Wait for it" }) }
                        },
                        label = { Text("Top Overlay Banner Text") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overlay_text_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Spoken Video Auto-Captions", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Uses live broadcast spoken captions (timedtext) when available — never records chat comments",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.autoCaptionsEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateConfig { it.copy(autoCaptionsEnabled = enabled) }
                            },
                            modifier = Modifier.testTag("auto_captions_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = studio.velocityCyan
                            )
                        )
                    }

                    if (config.autoCaptionsEnabled) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CaptionStyle.entries.forEach { style ->
                                FilterChip(
                                    selected = config.captionStyle == style,
                                    onClick = { onUpdateConfig { it.copy(captionStyle = style) } },
                                    label = { Text(style.displayName) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = studio.velocityCyanContainer,
                                        selectedLabelColor = studio.velocityCyan
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Resource Footprint & Output Target
        item(key = "resource_tuning") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Memory,
                            contentDescription = null,
                            tint = studio.targetEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Resource Footprint & Multi-Channel Cap",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VideoQualityProfile.entries.forEach { profile ->
                            FilterChip(
                                selected = config.videoProfile == profile,
                                onClick = { onUpdateConfig { it.copy(videoProfile = profile) } },
                                label = { Text(profile.displayName) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = studio.targetEmeraldContainer,
                                    selectedLabelColor = studio.targetEmerald
                                )
                            )
                        }
                    }

                    Text(
                        text = "${config.videoProfile.footprintLabel} • Hardware H.264 single-worker encoder queue keeps heap usage stable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Max Simultaneous Channels", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${config.maxConcurrentChannels} channels",
                            style = MaterialTheme.typography.labelLarge,
                            color = studio.targetEmerald
                        )
                    }
                    Slider(
                        value = config.maxConcurrentChannels.toFloat(),
                        onValueChange = { v ->
                            onUpdateConfig { it.copy(maxConcurrentChannels = v.roundToInt().coerceIn(1, 12)) }
                        },
                        valueRange = 1f..12f,
                        steps = 10,
                        colors = SliderDefaults.colors(
                            thumbColor = studio.targetEmerald,
                            activeTrackColor = studio.targetEmerald
                        )
                    )
                }
            }
        }
    }
}
