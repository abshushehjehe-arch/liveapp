package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.local.RecordedClipEntity
import com.example.ui.theme.LocalStudioColors
import java.io.File
import java.util.Locale

@Composable
fun ClipPlayerScreen(
    clip: RecordedClipEntity,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val studio = LocalStudioColors.current
    var isPlaying by remember { mutableStateOf(true) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    val playableUri = remember(clip.contentUri, clip.filePath) {
        when {
            clip.contentUri.isNotBlank() -> Uri.parse(clip.contentUri)
            clip.filePath.isNotBlank() -> Uri.fromFile(File(clip.filePath))
            else -> null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("player_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Column {
                    Text(
                        text = clip.channelTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${clip.width}×${clip.height} • ${clip.durationSeconds}s • ${clip.aspectRatio}",
                        style = MaterialTheme.typography.labelSmall,
                        color = studio.velocityCyan
                    )
                }
            }

            IconButton(
                onClick = {
                    val shareUri = resolveShareUri(context, clip) ?: return@IconButton
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share 9:16 Clip"))
                },
                modifier = Modifier.testTag("player_share_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.share_clip),
                    tint = studio.velocityCyan
                )
            }
        }

        // 9:16 Local MP4 Video Player
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(460.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            ) {
                if (playableUri != null) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(playableUri)
                                setOnPreparedListener { mp ->
                                    mp.setVolume(1f, 1f)
                                    mp.isLooping = true
                                    start()
                                    isPlaying = true
                                }
                                videoViewRef = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Playback Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    val vv = videoViewRef
                    if (vv != null) {
                        if (vv.isPlaying) {
                            vv.pause()
                            isPlaying = false
                        } else {
                            vv.start()
                            isPlaying = true
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("player_play_pause_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = studio.velocityCyan,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(if (isPlaying) R.string.pause_clip else R.string.play_clip))
            }

            OutlinedButton(
                onClick = {
                    videoViewRef?.stopPlayback()
                    onDelete()
                },
                modifier = Modifier.testTag("player_delete_button"),
                border = BorderStroke(1.dp, studio.liveCrimson)
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = studio.liveCrimson,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.delete_clip), color = studio.liveCrimson)
            }
        }

        // Telemetry & Local File Info
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Spike & Storage Metadata",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "Peak %.0f msg/min (%.1fx baseline) • Top Overlay: \"%s\"",
                        clip.peakVelocityPerMin,
                        clip.spikeMultiplier,
                        clip.overlayText
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = studio.thresholdAmber
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "Saved: %s (%.2f MB • encoded in %.1fs)",
                        clip.filePath,
                        clip.fileSizeBytes / (1024f * 1024f),
                        clip.processingTimeMs / 1000f
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (clip.captionsSummary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Burned Auto-Captions",
                        style = MaterialTheme.typography.labelMedium,
                        color = studio.velocityCyan
                    )
                    Text(
                        text = clip.captionsSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private fun resolveShareUri(context: android.content.Context, clip: RecordedClipEntity): Uri? {
    if (clip.contentUri.startsWith("content://")) {
        return Uri.parse(clip.contentUri)
    }
    val file = File(clip.filePath)
    if (file.exists()) {
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }
    return null
}
