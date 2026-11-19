package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.EmbeddedPlayerManager
import com.example.ui.AddChannelState
import com.example.ui.PulseClipUiState
import com.example.ui.components.ActiveClipProgressBanner
import com.example.ui.components.ChannelMonitorCard
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.MonoMetricLarge
import java.util.Locale

@Composable
fun MonitorScreen(
    uiState: PulseClipUiState,
    embeddedPlayerManager: EmbeddedPlayerManager,
    needsNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onToggleEngine: () -> Unit,
    onForcePoll: () -> Unit,
    onAddChannel: (String, () -> Unit) -> Unit,
    onClearAddError: () -> Unit,
    onToggleChannelEnabled: (String, Boolean) -> Unit,
    onRemoveChannel: (String) -> Unit,
    onToggleManualClipCut: (Boolean) -> Unit,
    onManualClip: (com.example.data.local.MonitoredChannelEntity) -> Unit,
    onStartManualCut: (com.example.data.local.MonitoredChannelEntity) -> Unit,
    onStopManualCut: (String) -> Unit,
    onCancelManualCut: (String) -> Unit,
    onTogglePlayerMute: (String) -> Unit,
    onReloadPlayer: (String) -> Unit,
    onOpenYouTubeSignIn: () -> Unit,
    onOpenClip: (Long) -> Unit,
    onDismissBanner: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.monitor_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.monitor_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val anyLoginNeeded = uiState.playerStatusByChannel.values.any { it.loginRequired }
                OutlinedButton(
                    onClick = onOpenYouTubeSignIn,
                    modifier = Modifier.testTag("youtube_signin_header_button"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    border = BorderStroke(
                        1.dp,
                        when {
                            anyLoginNeeded -> studio.liveCrimson
                            uiState.isYouTubeSignedIn -> studio.targetEmerald.copy(alpha = 0.65f)
                            else -> studio.thresholdAmber.copy(alpha = 0.75f)
                        }
                    )
                ) {
                    Icon(
                        imageVector = if (uiState.isYouTubeSignedIn && !anyLoginNeeded) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Filled.AccountCircle
                        },
                        contentDescription = null,
                        tint = when {
                            anyLoginNeeded -> studio.liveCrimson
                            uiState.isYouTubeSignedIn -> studio.targetEmerald
                            else -> studio.thresholdAmber
                        },
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = stringResource(
                            if (uiState.isYouTubeSignedIn && !anyLoginNeeded) {
                                R.string.youtube_signed_in_badge
                            } else {
                                R.string.youtube_signin_action
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            anyLoginNeeded -> studio.liveCrimson
                            uiState.isYouTubeSignedIn -> studio.targetEmerald
                            else -> studio.thresholdAmber
                        }
                    )
                }
                IconButton(
                    onClick = onForcePoll,
                    modifier = Modifier.testTag("force_poll_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.force_refresh),
                        tint = studio.velocityCyan
                    )
                }
            }
        }

        // Master Engine Control Banner
        item(key = "engine_deck") {
            MasterEngineControlCard(
                uiState = uiState,
                onToggleEngine = onToggleEngine,
                onToggleManualClipCut = onToggleManualClipCut
            )
        }

        // Live Recording -> 9:16 Encoding -> Saving Progress Bar
        item(key = "active_clip_progress") {
            ActiveClipProgressBanner(
                activeRecording = uiState.activeRecording,
                queueTelemetry = uiState.queueTelemetry,
                activePipelineJobs = uiState.activePipelineJobs,
                onOpenClip = onOpenClip,
                onDismiss = onDismissBanner,
                onAttachScreenBridge = if (!uiState.isScreenCaptureActive) onRequestScreenCapture else null,
                manualClipCutEnabled = uiState.config.manualClipCutEnabled,
                onStopManualCut = onStopManualCut,
                onCancelManualCut = onCancelManualCut
            )
        }

        // Runtime Notification Permission Banner on Android 13+
        item(key = "notif_permission") {
            AnimatedVisibility(visible = needsNotificationPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = studio.velocityCyanContainer),
                    border = BorderStroke(1.dp, studio.velocityCyan.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.NotificationsActive,
                            contentDescription = null,
                            tint = studio.velocityCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.notification_permission_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.notification_permission_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = onRequestNotificationPermission,
                            modifier = Modifier.testTag("grant_notification_button")
                        ) {
                            Text(
                                text = stringResource(R.string.grant_permission),
                                color = studio.velocityCyan
                            )
                        }
                    }
                }
            }
        }

        // Section Header + Add Channel Action
        item(key = "channels_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Streams (${uiState.channels.size}/${uiState.config.maxConcurrentChannels})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                OutlinedButton(
                    onClick = {
                        onClearAddError()
                        showAddDialog = true
                    },
                    modifier = Modifier.testTag("add_channel_button"),
                    border = BorderStroke(1.dp, studio.velocityCyan.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = studio.velocityCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.add_channel),
                        color = studio.velocityCyan
                    )
                }
            }
        }

        if (uiState.channels.isEmpty()) {
            item(key = "empty_channels") {
                EmptyChannelsCard(
                    onAddClick = {
                        onClearAddError()
                        showAddDialog = true
                    }
                )
            }
        } else {
            items(
                items = uiState.channels,
                key = { it.channelId }
            ) { channel ->
                ChannelMonitorCard(
                    channel = channel,
                    telemetry = uiState.telemetryByChannel[channel.channelId],
                    playerStatus = uiState.playerStatusByChannel[channel.channelId],
                    embeddedPlayerManager = embeddedPlayerManager,
                    config = uiState.config,
                    onToggleEnabled = { enabled -> onToggleChannelEnabled(channel.channelId, enabled) },
                    onRemove = { onRemoveChannel(channel.channelId) },
                    onManualClip = { onManualClip(channel) },
                    onStartManualCut = { onStartManualCut(channel) },
                    onStopManualCut = { onStopManualCut(channel.channelId) },
                    onCancelManualCut = { onCancelManualCut(channel.channelId) },
                    onToggleMute = { onTogglePlayerMute(channel.channelId) },
                    onReloadPlayer = { onReloadPlayer(channel.channelId) },
                    onSignInYouTube = onOpenYouTubeSignIn
                )
            }
        }
    }

    if (showAddDialog) {
        AddChannelDialog(
            state = uiState.addChannelState,
            onDismiss = {
                onClearAddError()
                showAddDialog = false
            },
            onConfirm = { input ->
                onAddChannel(input) {
                    showAddDialog = false
                }
            }
        )
    }
}

@Composable
private fun MasterEngineControlCard(
    uiState: PulseClipUiState,
    onToggleEngine: () -> Unit,
    onToggleManualClipCut: (Boolean) -> Unit
) {
    val studio = LocalStudioColors.current
    val rate = uiState.resourceSnapshot.clipsPerFiveMin
    val targetMet = rate >= uiState.config.targetClipsPerFiveMin.toFloat()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(
            width = 1.dp,
            color = if (uiState.isEngineRunning) studio.velocityCyan.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = if (uiState.isEngineRunning) {
                            listOf(
                                studio.velocityCyanContainer.copy(alpha = 0.65f),
                                MaterialTheme.colorScheme.surfaceContainer
                            )
                        } else {
                            listOf(
                                MaterialTheme.colorScheme.surfaceContainer,
                                studio.rackSurface
                            )
                        }
                    )
                )
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (uiState.isEngineRunning) studio.targetEmerald else studio.mutedText
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (uiState.isEngineRunning) R.string.engine_running else R.string.engine_idle
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (uiState.isEngineRunning) studio.targetEmerald
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = onToggleEngine,
                        modifier = Modifier.testTag("toggle_engine_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isEngineRunning) studio.liveCrimson else studio.velocityCyan,
                            contentColor = if (uiState.isEngineRunning) {
                                MaterialTheme.colorScheme.onSecondary
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            }
                        )
                    ) {
                        Icon(
                            imageVector = if (uiState.isEngineRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                if (uiState.isEngineRunning) R.string.engine_stop else R.string.engine_start
                            )
                        )
                    }
                }

                // 4 Precision Telemetry KPIs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KpiCell(
                        label = "OUTPUT / 5M",
                        value = String.format(Locale.US, "%.1f", rate),
                        sub = "Target ≥${uiState.config.targetClipsPerFiveMin}",
                        valueColor = if (targetMet) studio.targetEmerald else studio.velocityCyan,
                        modifier = Modifier.weight(1f)
                    )
                    KpiCell(
                        label = "LIVE NOW",
                        value = "${uiState.liveChannelCount}",
                        sub = "${uiState.resourceSnapshot.activeWorkers} workers",
                        valueColor = if (uiState.liveChannelCount > 0) studio.liveCrimson else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    KpiCell(
                        label = "SAVED CLIPS",
                        value = "${uiState.savedClips.size}",
                        sub = "${uiState.activePipelineJobs.size} queued",
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    KpiCell(
                        label = "FOOTPRINT",
                        value = "${uiState.resourceSnapshot.usedHeapMb}M",
                        sub = "Heap RAM",
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Manual Clip Cut Master ON/OFF Toggle Strip
                val manualOn = uiState.config.manualClipCutEnabled
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (manualOn) studio.velocityCyanContainer.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCut,
                            contentDescription = null,
                            tint = if (manualOn) studio.velocityCyan else studio.mutedText,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.manual_cut_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(
                                    if (manualOn) R.string.manual_cut_subtitle_on
                                    else R.string.manual_cut_subtitle_off
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = manualOn,
                        onCheckedChange = onToggleManualClipCut,
                        modifier = Modifier.testTag("manual_cut_master_toggle"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = studio.velocityCyan
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun KpiCell(
    label: String,
    value: String,
    sub: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = studio.mutedText,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MonoMetricLarge,
            color = valueColor,
            maxLines = 1
        )
        Text(
            text = sub,
            style = MaterialTheme.typography.labelSmall,
            color = studio.mutedText,
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyChannelsCard(onAddClick: () -> Unit) {
    val studio = LocalStudioColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.img_studio_radar_1795092446707),
                contentDescription = "PulseClip live monitoring radar illustration",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
            )
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.empty_channels_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.empty_channels_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onAddClick,
                    modifier = Modifier.testTag("empty_add_channel_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = studio.velocityCyan,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.add_channel))
                }
            }
        }
    }
}

@Composable
private fun AddChannelDialog(
    state: AddChannelState,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    val studio = LocalStudioColors.current
    val isLoading = state is AddChannelState.Resolving
    val parsedLines = com.example.data.youtube.YouTubeRepository.parseMultiLineInputs(input)
    val directLinkCount = parsedLines.count {
        com.example.data.youtube.YouTubeRepository.extractVideoIdFromInput(it) != null
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Text(
                text = stringResource(R.string.add_channel_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Paste 1 to 6 YouTube Live Video links (one per line — youtube.com/live/…, watch?v=…, or youtu.be/…). Direct live links play in 1080p & measure chat velocity inside the player — works even when Wi-Fi blocks YouTube API!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.add_channel_hint)) },
                    placeholder = {
                        Text(
                            text = "https://youtube.com/live/VIDEO_1\nhttps://youtube.com/watch?v=VIDEO_2\nhttps://youtu.be/VIDEO_3",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("channel_handle_input")
                )
                if (directLinkCount > 0) {
                    Text(
                        text = "$directLinkCount direct live video link(s) detected • Wi-Fi safe player mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = studio.targetEmerald,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (state is AddChannelState.Error) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = studio.liveCrimson
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(input) },
                enabled = !isLoading && input.isNotBlank(),
                modifier = Modifier.testTag("confirm_add_channel_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = studio.velocityCyan,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = if (parsedLines.size > 1) "Add ${parsedLines.size} Streams"
                        else stringResource(R.string.add_channel_confirm)
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
