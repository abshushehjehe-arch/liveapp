package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM monitored_channels ORDER BY isLive DESC, addedAtMillis DESC")
    fun observeAllChannels(): Flow<List<MonitoredChannelEntity>>

    @Query("SELECT * FROM monitored_channels WHERE isMonitoringEnabled = 1")
    suspend fun getEnabledChannels(): List<MonitoredChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannel(channel: MonitoredChannelEntity)

    @Query("UPDATE monitored_channels SET isMonitoringEnabled = :enabled WHERE channelId = :channelId")
    suspend fun setMonitoringEnabled(channelId: String, enabled: Boolean)

    @Query(
        """
        UPDATE monitored_channels SET
            lastCheckedAtMillis = :checkedAt,
            isLive = :isLive,
            activeVideoId = :videoId,
            activeLiveTitle = :liveTitle,
            activeLiveChatId = :liveChatId,
            concurrentViewers = :viewers,
            lastError = :error
        WHERE channelId = :channelId
        """
    )
    suspend fun updateLiveStatus(
        channelId: String,
        checkedAt: Long,
        isLive: Boolean,
        videoId: String?,
        liveTitle: String?,
        liveChatId: String?,
        viewers: Long,
        error: String?
    )

    @Query(
        """
        UPDATE monitored_channels SET
            baselineVelocityPerMin = :baseline,
            currentVelocityPerMin = :current
        WHERE channelId = :channelId
        """
    )
    suspend fun updateVelocity(channelId: String, baseline: Float, current: Float)

    @Query("UPDATE monitored_channels SET title = :title, activeLiveTitle = COALESCE(:liveTitle, activeLiveTitle) WHERE channelId = :channelId")
    suspend fun updateTitleAndLiveTitle(channelId: String, title: String, liveTitle: String?)

    @Query("UPDATE monitored_channels SET totalClipsCaptured = totalClipsCaptured + 1 WHERE channelId = :channelId")
    suspend fun incrementClipCount(channelId: String)

    @Query("DELETE FROM monitored_channels WHERE channelId = :channelId")
    suspend fun deleteChannel(channelId: String)
}

@Dao
interface ClipDao {
    @Query("SELECT * FROM recorded_clips ORDER BY createdAtMillis DESC")
    fun observeAllClips(): Flow<List<RecordedClipEntity>>

    @Query("SELECT * FROM recorded_clips WHERE id = :id")
    fun observeClipById(id: Long): Flow<RecordedClipEntity?>

    @Query("SELECT * FROM recorded_clips WHERE id = :id")
    suspend fun getClipById(id: Long): RecordedClipEntity?

    @Query("SELECT COUNT(*) FROM recorded_clips WHERE status = 'SAVED' AND createdAtMillis >= :sinceMillis")
    fun observeSavedCountSince(sinceMillis: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClip(clip: RecordedClipEntity): Long

    @Query("UPDATE recorded_clips SET status = :status, progressPercent = :progress, errorMessage = :error WHERE id = :id")
    suspend fun updateProgress(id: Long, status: String, progress: Int, error: String? = null)

    @Query(
        """
        UPDATE recorded_clips SET
            status = :status,
            progressPercent = 100,
            filePath = :filePath,
            contentUri = :contentUri,
            thumbnailPath = :thumbnailPath,
            fileSizeBytes = :fileSizeBytes,
            processingTimeMs = :processingTimeMs,
            errorMessage = NULL
        WHERE id = :id
        """
    )
    suspend fun markSaved(
        id: Long,
        status: String = ClipStatus.SAVED,
        filePath: String,
        contentUri: String,
        thumbnailPath: String,
        fileSizeBytes: Long,
        processingTimeMs: Long
    )

    @Query("DELETE FROM recorded_clips WHERE id = :id")
    suspend fun deleteClip(id: Long)
}

@Database(
    entities = [MonitoredChannelEntity::class, RecordedClipEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PulseClipDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun clipDao(): ClipDao

    companion object {
        fun create(context: Context): PulseClipDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PulseClipDatabase::class.java,
                "pulseclip_local.db"
            ).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
}
