package com.streamvault.data.local.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index

@Entity(
    tableName = "external_audio_sources",
    primaryKeys = ["provider_id", "channel_id"],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "stream_id"], unique = true)
    ]
)
data class ExternalAudioSourceEntity(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "stream_id") val streamId: Long,
    val name: String,
    @ColumnInfo(name = "logo_url") val logoUrl: String? = null,
    @ColumnInfo(name = "group_title") val groupTitle: String? = null,
    @ColumnInfo(name = "source_url") val sourceUrl: String,
    @ColumnInfo(name = "resolved_url") val resolvedUrl: String,
    @ColumnInfo(name = "headers_json") val headersJson: String = "{}",
    @ColumnInfo(name = "user_agent") val userAgent: String? = null,
    @ColumnInfo(name = "expiration_time") val expirationTime: Long? = null,
    @ColumnInfo(name = "container_extension") val containerExtension: String? = null,
    @ColumnInfo(name = "prepared_at") val preparedAt: Long = System.currentTimeMillis()
)
