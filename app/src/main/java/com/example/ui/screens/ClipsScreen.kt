package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.ClipStatus
import com.example.data.local.RecordedClipEntity
import com.example.ui.PulseClipUiState
import com.example.ui.components.ActiveClipProgressBanner
import com.example.ui.theme.LocalStudioColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ClipsScreen(
    uiState: PulseClipUiState,
    onSelectClip: (Long) -> Unit,
    onDeleteClip: (RecordedClipEntity) -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current
    var selectedChannelFilter by rememberSaveable { mutableStateOf("ALL") }

    val channelNames = uiState.clips.map { it.channelTitle }.distinct()
    val filteredClips = if (selectedChannelFilter == "ALL") {
        uiState.clips
    } else {
        uiState.clips.filter { it.channelTitle == selectedChannelFilter }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 164.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "clips_header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text(
                        text = stringResource(R.string.clips_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.clips_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ActiveClipProgressBanner(
                    activeRecording = uiState.activeRecording,
                    queueTelemetry = uiState.queueTelemetry,
                    activePipelineJobs = uiState.activePipelineJobs,
                    onOpenClip = onSelectClip,
                    onDismiss = onDismissBanner
                )

                // Local Storage Summary Banner
                val totalMb = uiState.totalSavedBytes / (1024f * 1024f)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                tint = studio.targetEmerald,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Movies/PulseClip",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${uiState.savedClips.size} vertical 9:16 MP4 clip(s) on device",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = String.format(Locale.US, "%.1f MB", totalMb),
                            style = MaterialTheme.typography.labelLarge,
                            color = studio.targetEmerald
                        )
                    }
                }

                if (channelNames.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = selectedChannelFilter == "ALL",
                                onClick = { selectedChannelFilter = "ALL" },
                                label = { Text("All (${uiState.clips.size})") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = studio.velocityCyanContainer,
                                    selectedLabelColor = studio.velocityCyan
                                )
                            )
                        }
                        items(channelNames) { name ->
                            FilterChip(
                                selected = selectedChannelFilter == name,
                                onClick = { selectedChannelFilter = name },
                                label = { Text(name) },
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

        if (filteredClips.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty_clips") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(studio.elevatedCard),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VideoLibrary,
                                contentDescription = null,
                                tint = studio.velocityCyan,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.empty_clips_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.empty_clips_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredClips, key = { it.id }) { clip ->
                VerticalClipGridCard(
                    clip = clip,
                    activeStageLabel = if (uiState.queueTelemetry.activeClipId == clip.id) {
                        uiState.queueTelemetry.activeStageLabel
                    } else {
                        null
                    },
                    onClick = { onSelectClip(clip.id) },
                    onDelete = { onDeleteClip(clip) }
                )
            }
        }
    }
}

@Composable
private fun VerticalClipGridCard(
    clip: RecordedClipEntity,
    activeStageLabel: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val studio = LocalStudioColors.current
    val isReady = clip.status == ClipStatus.SAVED
    val isFailed = clip.status == ClipStatus.FAILED
    val isProcessing = clip.status == ClipStatus.QUEUED || clip.status == ClipStatus.ENCODING
    val timeLabel = rememberTimeLabel(clip.createdAtMillis)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("clip_card_${clip.id}")
            .clickable(enabled = isReady, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(
            1.dp,
            when {
                isFailed -> studio.liveCrimson.copy(alpha = 0.75f)
                isProcessing -> studio.velocityCyan.copy(alpha = 0.75f)
                else -> MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 14.5f)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (clip.thumbnailPath.isNotBlank() && File(clip.thumbnailPath).exists()) {
                AsyncImage(
                    model = File(clip.thumbnailPath),
                    contentDescription = "${clip.channelTitle} clip preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Bottom readability scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.15f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.08f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.92f)
                            )
                        )
                    )
            )

            // Spike Badge Top-Left
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isFailed) studio.thresholdAmber.copy(alpha = 0.92f)
                        else studio.liveCrimson.copy(alpha = 0.92f)
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isFailed) "FAILED" else String.format(Locale.US, "%.1fx SPIKE", clip.spikeMultiplier),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondary,
                    fontWeight = FontWeight.Bold
                )
            }

            // Duration Badge or Delete Button Top-Right
            if (isFailed) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .testTag("delete_failed_clip_${clip.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Delete failed clip",
                        tint = studio.liveCrimson
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.80f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "0:%02d", clip.durationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Center Play Icon, Encoding Progress, or Failure Icon
            if (isReady) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.play_clip),
                        tint = studio.velocityCyan,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else if (isProcessing) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.86f))
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${clip.progressPercent}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = studio.velocityCyan,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = { (clip.progressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = studio.velocityCyan,
                        trackColor = studio.velocityCyanContainer
                    )
                    Text(
                        text = activeStageLabel?.ifBlank { null } ?: if (clip.status == ClipStatus.QUEUED) "Queued to save…" else "Saving 9:16 MP4…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (isFailed) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.86f))
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = "Encoding failed",
                        tint = studio.liveCrimson,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = clip.errorMessage?.take(72) ?: "Could not save clip",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Bottom Metadata
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = clip.channelTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${clip.aspectRatio} • ${clip.captionCount} captions • $timeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun rememberTimeLabel(timestampMs: Long): String {
    return SimpleDateFormat("HH:mm", Locale.US).format(Date(timestampMs))
}
