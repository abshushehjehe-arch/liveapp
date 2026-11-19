package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.RecordedClipEntity
import com.example.engine.ActiveRecordingBannerState
import com.example.engine.PipelineQueueTelemetry
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.MonoMetricMedium
import java.util.Locale

@Composable
fun ActiveClipProgressBanner(
    activeRecording: ActiveRecordingBannerState?,
    queueTelemetry: PipelineQueueTelemetry,
    activePipelineJobs: List<RecordedClipEntity>,
    onOpenClip: (Long) -> Unit,
    onDismiss: () -> Unit,
    onAttachScreenBridge: (() -> Unit)? = null,
    manualClipCutEnabled: Boolean = false,
    onStopManualCut: ((String) -> Unit)? = null,
    onCancelManualCut: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isEncoding = queueTelemetry.activeClipId != null || activePipelineJobs.isNotEmpty()
    val hasSaved = !isEncoding && activeRecording == null && queueTelemetry.lastSavedClipId != null
    val hasError = !isEncoding && activeRecording == null && !queueTelemetry.lastErrorMessage.isNullOrBlank()
    val isVisible = activeRecording != null || isEncoding || hasSaved || hasError

    AnimatedVisibility(visible = isVisible, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("active_clip_progress_banner"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (activeRecording != null) {
                RecordingStageCard(
                    recording = activeRecording,
                    showManualCutActions = manualClipCutEnabled && onStopManualCut != null,
                    onStopAndSave = { onStopManualCut?.invoke(activeRecording.channelId) },
                    onCancel = if (activeRecording.isManualCut && onCancelManualCut != null) {
                        { onCancelManualCut(activeRecording.channelId) }
                    } else {
                        null
                    }
                )
            }

            if (isEncoding) {
                val activeJob = activePipelineJobs.firstOrNull { it.id == queueTelemetry.activeClipId }
                    ?: activePipelineJobs.firstOrNull()
                val progressPercent = if (queueTelemetry.activeClipId != null) {
                    queueTelemetry.activeProgressPercent
                } else {
                    activeJob?.progressPercent ?: 0
                }
                val channelTitle = queueTelemetry.activeChannelTitle
                    ?: activeJob?.channelTitle
                    ?: "Live Clip"
                val stageLabel = queueTelemetry.activeStageLabel.ifBlank {
                    if (progressPercent >= 90) "Saving clip to Movies/PulseClip…"
                    else "Encoding 9:16 vertical MP4…"
                }
                val pendingCount = maxOf(queueTelemetry.queuedCount, activePipelineJobs.size)

                EncodingAndSavingStageCard(
                    channelTitle = channelTitle,
                    progressPercent = progressPercent,
                    stageLabel = stageLabel,
                    pendingCount = pendingCount
                )
            }

            if (hasSaved && queueTelemetry.lastSavedClipId != null) {
                SavedSuccessCard(
                    clipId = queueTelemetry.lastSavedClipId,
                    channelTitle = queueTelemetry.lastSavedChannelTitle ?: "Live Clip",
                    savedPath = queueTelemetry.lastSavedFilePath ?: "Movies/PulseClip",
                    onOpenClip = onOpenClip,
                    onDismiss = onDismiss
                )
            }

            if (hasError && !queueTelemetry.lastErrorMessage.isNullOrBlank()) {
                SaveErrorCard(
                    errorMessage = queueTelemetry.lastErrorMessage,
                    onAttachScreenBridge = onAttachScreenBridge,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun RecordingStageCard(
    recording: ActiveRecordingBannerState,
    showManualCutActions: Boolean,
    onStopAndSave: () -> Unit,
    onCancel: (() -> Unit)?
) {
    val studio = LocalStudioColors.current
    val target = recording.targetSeconds.coerceAtLeast(1)
    val rawProgress = (recording.elapsedSeconds.toFloat() / target.toFloat()).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(durationMillis = 300),
        label = "recordingProgress"
    )
    val percent = (rawProgress * 100f).toInt().coerceIn(0, 100)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recording_progress_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.5.dp, studio.liveCrimson.copy(alpha = 0.85f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(studio.liveCrimson)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (recording.isManualCut) {
                                "Manual Clip Cut • ${recording.channelTitle}"
                            } else {
                                "Recording Live Video • ${recording.channelTitle}"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = recording.stageLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = String.format(Locale.US, "%02ds / %02ds", recording.elapsedSeconds, target),
                    style = MonoMetricMedium,
                    color = studio.liveCrimson
                )
            }

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .testTag("recording_progress_bar"),
                color = studio.liveCrimson,
                trackColor = studio.liveCrimsonContainer
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PipelineStepPills(activeStep = 1)
                val mbText = if (recording.recordedMb > 0.05f) {
                    String.format(Locale.US, "%.1f MB • %d%%", recording.recordedMb, percent)
                } else {
                    "$percent%"
                }
                Text(
                    text = mbText,
                    style = MaterialTheme.typography.labelSmall,
                    color = studio.liveCrimson,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (showManualCutActions) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onStopAndSave,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("banner_stop_save_cut_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = studio.targetEmerald,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCut,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Stop & Save Cut")
                    }
                    if (onCancel != null) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.testTag("banner_cancel_cut_button"),
                            border = BorderStroke(1.dp, studio.liveCrimson.copy(alpha = 0.7f))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cancel manual cut",
                                tint = studio.liveCrimson,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel", color = studio.liveCrimson)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EncodingAndSavingStageCard(
    channelTitle: String,
    progressPercent: Int,
    stageLabel: String,
    pendingCount: Int
) {
    val studio = LocalStudioColors.current
    val clamped = progressPercent.coerceIn(0, 100)
    val animatedProgress by animateFloatAsState(
        targetValue = clamped / 100f,
        animationSpec = tween(durationMillis = 250),
        label = "encodingProgress"
    )
    val isSavingStage = clamped >= 90 || stageLabel.contains("Saving", ignoreCase = true)
    val accent = if (isSavingStage) studio.targetEmerald else studio.velocityCyan
    val track = if (isSavingStage) studio.targetEmeraldContainer else studio.velocityCyanContainer

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("encoding_progress_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.85f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                        imageVector = Icons.Filled.MovieCreation,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isSavingStage) {
                                "Saving Clip • $channelTitle"
                            } else {
                                "Processing 9:16 Clip • $channelTitle"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stageLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$clamped%",
                    style = MonoMetricMedium,
                    color = accent
                )
            }

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .testTag("encoding_progress_bar"),
                color = accent,
                trackColor = track
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PipelineStepPills(activeStep = if (isSavingStage) 3 else 2)
                if (pendingCount > 1) {
                    Text(
                        text = "+${pendingCount - 1} in queue",
                        style = MaterialTheme.typography.labelSmall,
                        color = studio.mutedText
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedSuccessCard(
    clipId: Long,
    channelTitle: String,
    savedPath: String,
    onOpenClip: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val studio = LocalStudioColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("clip_saved_banner"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = studio.targetEmeraldContainer.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, studio.targetEmerald.copy(alpha = 0.75f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Clip saved",
                tint = studio.targetEmerald,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "9:16 Clip Saved • $channelTitle",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = savedPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onOpenClip(clipId) },
                modifier = Modifier.testTag("play_saved_clip_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = studio.targetEmerald,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Play")
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_saved_banner_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss saved notice",
                    tint = studio.mutedText
                )
            }
        }
    }
}

@Composable
private fun SaveErrorCard(
    errorMessage: String,
    onAttachScreenBridge: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val studio = LocalStudioColors.current
    val showBridgeCta = onAttachScreenBridge != null &&
        errorMessage.contains("Screen", ignoreCase = true)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("clip_error_banner"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = studio.liveCrimsonContainer.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, studio.liveCrimson.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = "Save failed",
                    tint = studio.liveCrimson,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Clip Save Issue",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("dismiss_error_banner_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss error notice",
                        tint = studio.mutedText
                    )
                }
            }
            if (showBridgeCta && onAttachScreenBridge != null) {
                Button(
                    onClick = onAttachScreenBridge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("error_attach_screen_bridge_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = studio.velocityCyan,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Enable Screen + Audio Capture Bridge")
                }
            }
        }
    }
}

@Composable
private fun PipelineStepPills(activeStep: Int) {
    val studio = LocalStudioColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepChip(
            label = "1. Record",
            isActive = activeStep == 1,
            isDone = activeStep > 1,
            activeColor = studio.liveCrimson,
            doneColor = studio.targetEmerald
        )
        StepChip(
            label = "2. 9:16 Render",
            isActive = activeStep == 2,
            isDone = activeStep > 2,
            activeColor = studio.velocityCyan,
            doneColor = studio.targetEmerald
        )
        StepChip(
            label = "3. Save",
            isActive = activeStep == 3,
            isDone = false,
            activeColor = studio.targetEmerald,
            doneColor = studio.targetEmerald
        )
    }
}

@Composable
private fun StepChip(
    label: String,
    isActive: Boolean,
    isDone: Boolean,
    activeColor: Color,
    doneColor: Color
) {
    val studio = LocalStudioColors.current
    val fg = when {
        isActive -> activeColor
        isDone -> doneColor
        else -> studio.mutedText
    }
    val bg = when {
        isActive -> activeColor.copy(alpha = 0.16f)
        isDone -> doneColor.copy(alpha = 0.14f)
        else -> studio.elevatedCard
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        if (isActive) {
            Icon(
                imageVector = Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(6.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
        )
    }
}
