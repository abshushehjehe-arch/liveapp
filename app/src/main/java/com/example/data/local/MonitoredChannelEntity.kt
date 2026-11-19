package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monitored_channels")
data class MonitoredChannelEntity(
    @PrimaryKey val channelId: String,
    val handle: String,
    val title: String,
    val avatarUrl: String,
    val isMonitoringEnabled: Boolean = true,
    val addedAtMillis: Long = System.currentTimeMillis(),
    val lastCheckedAtMillis: Long = 0L,
    val isLive: Boolean = false,
    val activeVideoId: String? = null,
    val activeLiveTitle: String? = null,
    val activeLiveChatId: String? = null,
    val concurrentViewers: Long = 0L,
    val baselineVelocityPerMin: Float = 0f,
    val currentVelocityPerMin: Float = 0f,
    val totalClipsCaptured: Int = 0,
    val lastError: String? = null
)
