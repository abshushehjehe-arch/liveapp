package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.PulseClipApplication
import com.example.R
import com.example.engine.ChannelMonitorPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Play-policy compliant Foreground Service (dataSync | mediaProjection) keeping
 * simultaneous multi-stream YouTube monitoring and overnight 30–45s clip recording active 24×7.
 */
class StreamMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as PulseClipApplication
        val container = app.container

        when (intent?.action) {
            ACTION_STOP -> {
                container.monitorEngine.stopMonitoring()
                container.screenCaptureManager.stopCapture()
                releaseOvernightLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_ATTACH_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                acquireOvernightLocks()
                startAsForeground(
                    title = "PulseClip Live Bridge Active",
                    content = "Monitoring live streams with screen bridge attached",
                    includeMediaProjection = true
                )
                if (dataIntent != null) {
                    container.screenCaptureManager.startCapture(resultCode, dataIntent)
                    container.monitorEngine.appendLog("OK", "SCREEN", "Live MediaProjection capture attached")
                }
                container.monitorEngine.startMonitoring()
            }

            else -> {
                acquireOvernightLocks()
                startAsForeground(
                    title = "PulseClip 24×7 Auto-Clipper Active",
                    content = "Watching live streams & chat spikes overnight…",
                    includeMediaProjection = container.screenCaptureManager.isActive.value
                )
                container.monitorEngine.startMonitoring()
            }
        }

        // Keep notification synced with live engine telemetry
        serviceScope.launch {
            container.monitorEngine.state.collect { state ->
                if (!state.isRunning) return@collect
                val liveCount = state.channelTelemetry.values.count { it.isLive }
                val recCount = state.channelTelemetry.values.count { it.phase == ChannelMonitorPhase.RECORDING }
                val title = when {
                    recCount > 0 -> "Recording $recCount Spike Clip(s)…"
                    liveCount > 0 -> "Monitoring $liveCount Live Stream(s)"
                    else -> "PulseClip 24×7 Live Radar"
                }
                val rate = state.resourceSnapshot.clipsPerFiveMin
                val body = String.format(
                    "%d stream(s) • %.1f clips/5m • %d MB RAM",
                    state.resourceSnapshot.activeWorkers,
                    rate,
                    state.resourceSnapshot.usedHeapMb
                )
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(title, body))
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        releaseOvernightLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireOvernightLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulseClip::OvernightMonitorLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                // Up to 16 hours overnight continuous monitoring
                wakeLock?.acquire(16L * 60L * 60L * 1000L)
            }
        } catch (_: Exception) {
        }
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "PulseClip::OvernightWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseOvernightLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    private fun startAsForeground(
        title: String,
        content: String,
        includeMediaProjection: Boolean
    ) {
        val notification = buildNotification(title, content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (includeMediaProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, StreamMonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.engine_stop), stopPending)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "pulseclip_monitor_channel"
        const val NOTIFICATION_ID = 9016
        const val ACTION_START = "com.example.action.START_MONITOR"
        const val ACTION_STOP = "com.example.action.STOP_MONITOR"
        const val ACTION_ATTACH_PROJECTION = "com.example.action.ATTACH_PROJECTION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        fun start(context: Context) {
            val intent = Intent(context, StreamMonitorService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, StreamMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun startWithProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, StreamMonitorService::class.java).apply {
                action = ACTION_ATTACH_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_PROJECTION_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
