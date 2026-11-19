package com.example

import android.app.Application
import android.content.Context
import com.example.data.local.PulseClipDatabase
import com.example.data.prefs.SettingsRepository
import com.example.data.youtube.YouTubeRepository
import com.example.engine.ClipProcessingPipeline
import com.example.engine.EmbeddedPlayerManager
import com.example.engine.LiveVideoRecorder
import com.example.engine.MultiChannelMonitorEngine
import com.example.engine.ScreenCaptureManager
import com.example.engine.VerticalClipEncoder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: PulseClipDatabase = PulseClipDatabase.create(appContext)
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            }
        )
        .build()

    val youTubeRepository: YouTubeRepository = YouTubeRepository(
        httpClient = okHttpClient
    )

    val liveVideoRecorder: LiveVideoRecorder = LiveVideoRecorder(
        context = appContext,
        httpClient = okHttpClient
    )

    val embeddedPlayerManager: EmbeddedPlayerManager = EmbeddedPlayerManager(appContext)
    val screenCaptureManager: ScreenCaptureManager = ScreenCaptureManager(appContext)
    val verticalClipEncoder: VerticalClipEncoder = VerticalClipEncoder(appContext)

    private var monitorEngineRef: MultiChannelMonitorEngine? = null

    val clipProcessingPipeline: ClipProcessingPipeline = ClipProcessingPipeline(
        encoder = verticalClipEncoder,
        clipDao = database.clipDao(),
        channelDao = database.channelDao(),
        onLog = { level, tag, msg ->
            monitorEngineRef?.appendLog(level, tag, msg)
        }
    )

    val monitorEngine: MultiChannelMonitorEngine = MultiChannelMonitorEngine(
        context = appContext,
        youTubeRepository = youTubeRepository,
        liveVideoRecorder = liveVideoRecorder,
        embeddedPlayerManager = embeddedPlayerManager,
        channelDao = database.channelDao(),
        settingsRepository = settingsRepository,
        pipeline = clipProcessingPipeline,
        screenCaptureManager = screenCaptureManager
    ).also {
        monitorEngineRef = it
    }
}

class PulseClipApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
