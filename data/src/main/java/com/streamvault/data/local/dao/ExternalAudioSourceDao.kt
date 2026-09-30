package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.streamvault.data.local.entity.ExternalAudioSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExternalAudioSourceDao {
    @Query("SELECT * FROM external_audio_sources WHERE provider_id = :providerId ORDER BY name COLLATE NOCASE ASC")
    suspend fun getByProvider(providerId: Long): List<ExternalAudioSourceEntity>

    @Query("SELECT * FROM external_audio_sources WHERE provider_id = :providerId AND channel_id = :channelId LIMIT 1")
    suspend fun get(providerId: Long, channelId: Long): ExternalAudioSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: ExternalAudioSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<ExternalAudioSourceEntity>)

    @Query("DELETE FROM external_audio_sources WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("DELETE FROM external_audio_sources WHERE provider_id = :providerId AND channel_id NOT IN (:channelIds)")
    suspend fun deleteStale(providerId: Long, channelIds: List<Long>)

    @Query("SELECT COUNT(*) FROM external_audio_sources WHERE provider_id = :providerId")
    suspend fun countByProvider(providerId: Long): Int
}
