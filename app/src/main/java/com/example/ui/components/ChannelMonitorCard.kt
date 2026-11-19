package com.example.ui.components

import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.MonitoredChannelEntity
import com.example.data.prefs.PipelineConfig
import com.example.engine.ChannelLiveTelemetry
import com.example.engine.ChannelMonitorPhase
import com.example.engine.EmbeddedPlayerManager
import com.example.engine.EmbeddedPlayerStatus
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.MonoMetricMedium
import java.util.Locale

@Composable
fun ChannelMonitorCard(
    channel: MonitoredChannelEntity,
    telemetry: ChannelLiveTelemetry?,
    playerStatus: EmbeddedPlayerStatus?,
    embeddedPlayerManager: EmbeddedPlayerManager,
    config: PipelineConfig,
    onToggleEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onManualClip: () -> Unit,
    onStartManualCut: () -> Unit,
    onStopManualCut: () -> Unit,
    onCancelManualCut: () -> Unit,
    onToggleMute: () -> Unit,
    onReloadPlayer: () -> Unit,
    onSignInYouTube: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current
    val phase = telemetry?.phase ?: if (channel.isLive) ChannelMonitorPhase.MONITORING else ChannelMonitorPhase.OFFLINE
    val isLive = telemetry?.isLive ?: channel.isLive
    val activeVideoId = telemetry?.activeVideoId ?: channel.activeVideoId
    val isRecording = phase == ChannelMonitorPhase.RECORDING || playerStatus?.isRecording == true

    // Live Video Folder defaults to expanded when channel is live so user sees the 1080p mini-player immediately
    var isVideoFolderExpanded by rememberSaveable(channel.channelId) { mutableStateOf(true) }

    val borderColor by animateColorAsState(
        targetValue = when {
            isRecording -> studio.liveCrimson
            isLive -> studio.velocityCyan.copy(alpha = 0.65f)
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        label = "channelCardBorder"
    )

    val badgeColor = when {
        isRecording -> studio.liveCrimson
        isLive -> studio.velocityCyan
        phase == ChannelMonitorPhase.ERROR -> studio.thresholdAmber
        else -> studio.mutedText
    }
    val badgeBg = when {
        isRecording -> studio.liveCrimsonContainer
        isLive -> studio.velocityCyanContainer
        phase == ChannelMonitorPhase.ERROR -> studio.thresholdAmberContainer
        else -> studio.elevatedCard
    }
    val badgeText = when {
        isRecording -> "REC ${telemetry?.recordingElapsedSec ?: 0}s"
        isLive -> "LIVE"
        phase == ChannelMonitorPhase.CONNECTING -> "POLLING"
        phase == ChannelMonitorPhase.COOLDOWN -> "COOLDOWN"
        phase == ChannelMonitorPhase.ERROR -> "RETRYING"
        else -> "OFFLINE"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("channel_card_${channel.channelId}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(if (isRecording) 1.5.dp else 1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(studio.elevatedCard),
                    contentAlignment = Alignment.Center
                ) {
                    if (channel.avatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = channel.avatarUrl,
                            contentDescription = "${channel.title} avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Sensors,
                            contentDescription = null,
                            tint = studio.velocityCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = channel.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(badgeBg)
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FiberManualRecord,
                                contentDescription = null,
                                tint = badgeColor,
                                modifier = Modifier.size(7.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    val liveTitle = telemetry?.activeLiveTitle ?: channel.activeLiveTitle
                    Text(
                        text = liveTitle ?: "${channel.handle} • ${channel.totalClipsCaptured} clip(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Switch(
                    checked = channel.isMonitoringEnabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("channel_toggle_${channel.channelId}"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = studio.velocityCyan
                    )
                )

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.testTag("channel_remove_${channel.channelId}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.remove_channel),
                        tint = studio.mutedText
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Live Velocity Sparkline + Threshold Readouts
            val velocity = telemetry?.currentVelocityPerMin ?: channel.currentVelocityPerMin
            val baseline = (telemetry?.baselineVelocityPerMin ?: channel.baselineVelocityPerMin).coerceAtLeast(2f)
            val multiplier = telemetry?.spikeMultiplier ?: (velocity / baseline)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "CHAT VELOCITY",
                        style = MaterialTheme.typography.labelSmall,
                        color = studio.mutedText
                    )
                    Text(
                        text = String.format(Locale.US, "%.0f msg/min", velocity),
                        style = MonoMetricMedium,
                        color = if (isRecording) studio.liveCrimson else MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "BASELINE",
                        style = MaterialTheme.typography.labelSmall,
                        color = studio.mutedText
                    )
                    Text(
                        text = String.format(Locale.US, "%.0f/m", baseline),
                        style = MonoMetricMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = String.format(Locale.US, "TRIGGER %.1fx", config.spikeMultiplier),
                        style = MaterialTheme.typography.labelSmall,
                        color = studio.thresholdAmber
                    )
                    Text(
                        text = String.format(Locale.US, "%.1fx", multiplier),
                        style = MonoMetricMedium,
                        color = when {
                            multiplier >= config.spikeMultiplier -> studio.liveCrimson
                            multiplier >= config.spikeMultiplier * 0.7f -> studio.thresholdAmber
                            else -> studio.velocityCyan
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            VelocitySparkline(
                samples = telemetry?.velocityHistory.orEmpty(),
                spikeMultiplier = config.spikeMultiplier,
                isRecording = isRecording
            )

            // Action Trigger Row (directly above the channel's Live Video Folder!)
            val statusNote = telemetry?.statusNote ?: channel.lastError
            if (!statusNote.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = statusNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (phase == ChannelMonitorPhase.ERROR) studio.thresholdAmber else studio.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Manual Clip Cut Controls (visible ONLY when Manual Clip Cut toggle is ON)
            if (config.manualClipCutEnabled && isLive && !isRecording) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onStartManualCut,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("start_manual_cut_${channel.channelId}"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = studio.velocityCyan,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCut,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.manual_cut_start),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    OutlinedButton(
                        onClick = onManualClip,
                        modifier = Modifier.testTag("manual_clip_${channel.channelId}"),
                        border = BorderStroke(1.dp, studio.velocityCyan.copy(alpha = 0.65f))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Videocam,
                            contentDescription = null,
                            tint = studio.velocityCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Quick ${config.minClipSeconds}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = studio.velocityCyan,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Live Recording Progress Bar right below trigger status
            if (isRecording) {
                val elapsed = telemetry?.recordingElapsedSec ?: 0
                val targetSec = (telemetry?.recordingTargetSec ?: config.maxClipSeconds).coerceAtLeast(1)
                val recordedMb = maxOf(
                    telemetry?.recordedVideoMb ?: 0f,
                    (playerStatus?.recordedBytes ?: 0L) / (1024f * 1024f)
                )
                val progress = (elapsed.toFloat() / targetSec.toFloat()).coerceIn(0f, 1f)
                val isManualCut = telemetry?.isManualCut == true
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isManualCut) Icons.Filled.ContentCut else Icons.Filled.Videocam,
                            contentDescription = null,
                            tint = studio.liveCrimson,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val capCount = playerStatus?.captionCount ?: 0
                        Text(
                            text = if (recordedMb > 0.05f) {
                                val capLabel = if (capCount > 0) " • $capCount caps" else ""
                                val prefix = if (isManualCut) "Manual Cut" else "Recording 1080p player"
                                String.format(Locale.US, "%s (%.1f MB%s)…", prefix, recordedMb, capLabel)
                            } else if (isManualCut) {
                                "Manual Cut active • Tap Stop & Save anytime"
                            } else if (elapsed < config.minClipSeconds) {
                                "Recording from live player (${targetSec}s target)…"
                            } else {
                                "Recording until action subsides (max ${targetSec}s)…"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = studio.liveCrimson,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = String.format(Locale.US, "%02ds / %02ds", elapsed, targetSec),
                        style = MaterialTheme.typography.labelMedium,
                        color = studio.liveCrimson,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .testTag("recording_progress_${channel.channelId}"),
                    color = studio.liveCrimson,
                    trackColor = studio.liveCrimsonContainer
                )
                if (config.manualClipCutEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onStopManualCut,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("stop_manual_cut_${channel.channelId}"),
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
                            Text(
                                text = stringResource(R.string.manual_cut_stop_save),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        if (isManualCut) {
                            OutlinedButton(
                                onClick = onCancelManualCut,
                                modifier = Modifier.testTag("cancel_manual_cut_${channel.channelId}"),
                                border = BorderStroke(1.dp, studio.liveCrimson.copy(alpha = 0.7f))
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.manual_cut_cancel),
                                    tint = studio.liveCrimson,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.manual_cut_cancel),
                                    color = studio.liveCrimson,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // Dedicated 1080p Live Mini-Player Folder directly underneath the channel's action trigger!
            Spacer(modifier = Modifier.height(10.dp))
            ChannelLiveMiniPlayerFolder(
                channelId = channel.channelId,
                isLive = isLive,
                activeVideoId = activeVideoId,
                isRecording = isRecording,
                recordingElapsedSec = telemetry?.recordingElapsedSec ?: 0,
                recordingTargetSec = (telemetry?.recordingTargetSec ?: config.maxClipSeconds).coerceAtLeast(1),
                recordedMb = maxOf(
                    telemetry?.recordedVideoMb ?: 0f,
                    (playerStatus?.recordedBytes ?: 0L) / (1024f * 1024f)
                ),
                playerStatus = playerStatus,
                embeddedPlayerManager = embeddedPlayerManager,
                isExpanded = isVideoFolderExpanded,
                onToggleExpanded = { isVideoFolderExpanded = !isVideoFolderExpanded },
                onToggleMute = onToggleMute,
                onReloadPlayer = onReloadPlayer,
                onSignInYouTube = onSignInYouTube
            )
        }
    }
}

@Composable
private fun ChannelLiveMiniPlayerFolder(
    channelId: String,
    isLive: Boolean,
    activeVideoId: String?,
    isRecording: Boolean,
    recordingElapsedSec: Int,
    recordingTargetSec: Int,
    recordedMb: Float,
    playerStatus: EmbeddedPlayerStatus?,
    embeddedPlayerManager: EmbeddedPlayerManager,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleMute: () -> Unit,
    onReloadPlayer: () -> Unit,
    onSignInYouTube: () -> Unit
) {
    val studio = LocalStudioColors.current
    val hasPlayableStream = isLive && !activeVideoId.isNullOrBlank()
    val qualityLabel = playerStatus?.qualityLabel ?: "1080p HD"
    val isMuted = playerStatus?.isMuted ?: true

    val folderBorder by animateColorAsState(
        targetValue = when {
            isRecording -> studio.liveCrimson
            hasPlayableStream -> studio.velocityCyan.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        },
        label = "playerFolderBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("video_folder_$channelId"),
        shape = RoundedCornerShape(14.dp),
        color = studio.rackSurface,
        border = BorderStroke(if (isRecording) 1.5.dp else 1.dp, folderBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Folder Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasPlayableStream, onClick = onToggleExpanded)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (hasPlayableStream && isExpanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                    contentDescription = null,
                    tint = when {
                        isRecording -> studio.liveCrimson
                        hasPlayableStream -> studio.velocityCyan
                        else -> studio.mutedText
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.live_video_folder),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (hasPlayableStream) MaterialTheme.colorScheme.onSurface else studio.mutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (hasPlayableStream) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (isRecording) studio.liveCrimsonContainer
                                        else studio.velocityCyanContainer
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isRecording) "REC • $qualityLabel" else qualityLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isRecording) studio.liveCrimson else studio.velocityCyan,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                    Text(
                        text = when {
                            !hasPlayableStream -> stringResource(R.string.live_video_folder_offline)
                            isRecording -> playerStatus?.statusNote ?: "Recording directly from mini-player…"
                            else -> playerStatus?.statusNote ?: "Playing live HD video in background"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRecording) studio.liveCrimson else studio.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (hasPlayableStream) {
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier.testTag("player_mute_$channelId")
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = stringResource(
                                if (isMuted) R.string.unmute_player else R.string.mute_player
                            ),
                            tint = if (isMuted) studio.mutedText else studio.velocityCyan,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    IconButton(
                        onClick = onReloadPlayer,
                        modifier = Modifier.testTag("player_reload_$channelId")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.reload_player),
                            tint = studio.mutedText,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    IconButton(
                        onClick = onToggleExpanded,
                        modifier = Modifier.testTag("player_folder_toggle_$channelId")
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = stringResource(R.string.toggle_video_folder),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Embedded 1080p Mini-Player Body
            AnimatedVisibility(visible = hasPlayableStream && isExpanded) {
                val safeVideoId = activeVideoId.orEmpty()
                var hostContainer by remember { mutableStateOf<FrameLayout?>(null) }

                DisposableEffect(channelId, safeVideoId) {
                    onDispose {
                        hostContainer?.let { host ->
                            embeddedPlayerManager.detachFromContainer(channelId, host)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .testTag("mini_player_surface_$channelId")
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            FrameLayout(ctx).also { frame ->
                                hostContainer = frame
                                embeddedPlayerManager.attachToContainer(channelId, safeVideoId, frame)
                            }
                        },
                        update = { frame ->
                            hostContainer = frame
                            embeddedPlayerManager.attachToContainer(channelId, safeVideoId, frame)
                        }
                    )

                    // Top-left live HD / REC indicator overlay
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FiberManualRecord,
                            contentDescription = null,
                            tint = if (isRecording) studio.liveCrimson else studio.targetEmerald,
                            modifier = Modifier.size(8.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isRecording) {
                                String.format(Locale.US, "REC %02ds / %02ds", recordingElapsedSec, recordingTargetSec)
                            } else {
                                "LIVE • $qualityLabel"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 1-Tap YouTube Login Overlay when YouTube asks to confirm you're not a bot
                    if (playerStatus?.loginRequired == true) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.82f))
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "YouTube Sign-In Required",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Log in once with your Google email to play all 1080p live videos",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = onSignInYouTube,
                                modifier = Modifier.testTag("player_signin_button_$channelId"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = studio.velocityCyan,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AccountCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sign In & Play Video", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    // Bottom in-player recording progress HUD while recording
                    if (isRecording) {
                        val recProgress = (recordingElapsedSec.toFloat() / recordingTargetSec.toFloat()).coerceIn(0f, 1f)
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.76f))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Capturing %s • %.1f MB",
                                        playerStatus?.captureMode ?: "HD Stream",
                                        recordedMb
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                                Text(
                                    text = "${(recProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = studio.liveCrimson,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { recProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = studio.liveCrimson,
                                trackColor = Color.White.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }
        }
    }
}
