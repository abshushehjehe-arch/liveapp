package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.EngineLogEntry
import com.example.ui.PulseClipUiState
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.MonoMetricMedium
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    uiState: PulseClipUiState,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current
    val res = uiState.resourceSnapshot
    val heapRatio = (res.usedHeapMb.toFloat() / res.maxHeapMb.toFloat()).coerceIn(0.02f, 1f)
    val rateRatio = (res.clipsPerFiveMin / uiState.config.targetClipsPerFiveMin.toFloat()).coerceIn(0f, 1f)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "diag_header") {
            Column {
                Text(
                    text = stringResource(R.string.diagnostics_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.diagnostics_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Resource & Throughput Card
        item(key = "resource_card") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Heap Usage
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("JVM Memory Footprint", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${res.usedHeapMb} MB / ${res.maxHeapMb} MB",
                                style = MonoMetricMedium,
                                color = if (heapRatio > 0.75f) studio.thresholdAmber else studio.targetEmerald
                            )
                        }
                        LinearProgressIndicator(
                            progress = { heapRatio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (heapRatio > 0.75f) studio.thresholdAmber else studio.targetEmerald,
                            trackColor = studio.elevatedCard
                        )
                    }

                    // 5-Minute Clip Throughput
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Automated Output Rate (5m Rolling)", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%.1f / %d target",
                                    res.clipsPerFiveMin,
                                    uiState.config.targetClipsPerFiveMin
                                ),
                                style = MonoMetricMedium,
                                color = if (rateRatio >= 1f) studio.targetEmerald else studio.velocityCyan
                            )
                        }
                        LinearProgressIndicator(
                            progress = { rateRatio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (rateRatio >= 1f) studio.targetEmerald else studio.velocityCyan,
                            trackColor = studio.elevatedCard
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "YouTube Live Feed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "KEYLESS DIRECT",
                            style = MaterialTheme.typography.labelMedium,
                            color = studio.targetEmerald
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Concurrency Guards",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${res.activeWorkers} poll workers • 1 HW encoder slot",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        item(key = "log_header") {
            Text(
                text = "Live Engine Telemetry Log (${uiState.logs.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (uiState.logs.isEmpty()) {
            item(key = "empty_logs") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Text(
                        text = "Start the monitoring engine to inspect keyless YouTube live detection, 3x velocity triggers, and 9:16 local MP4 encoding events.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(uiState.logs, key = { "${it.timestampMs}_${it.tag}_${it.message.hashCode()}" }) { entry ->
                EngineLogRow(entry = entry)
            }
        }
    }
}

@Composable
private fun EngineLogRow(entry: EngineLogEntry) {
    val studio = LocalStudioColors.current
    val badgeColor = when (entry.level) {
        "SPIKE" -> studio.liveCrimson
        "OK" -> studio.targetEmerald
        "ERR" -> studio.liveCrimson
        "WARN" -> studio.thresholdAmber
        else -> studio.velocityCyan
    }
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timestampMs))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeStr,
            style = MaterialTheme.typography.labelSmall,
            color = studio.mutedText
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = entry.tag,
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
