package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object FeatureMigrationsV78To79 {
    val MIGRATION_78_79 = object : Migration(78, 79) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS external_audio_sources (
                    provider_id INTEGER NOT NULL,
                    channel_id INTEGER NOT NULL,
                    stream_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    logo_url TEXT,
                    group_title TEXT,
                    source_url TEXT NOT NULL,
                    resolved_url TEXT NOT NULL,
                    headers_json TEXT NOT NULL DEFAULT '{}',
                    user_agent TEXT,
                    expiration_time INTEGER,
                    container_extension TEXT,
                    prepared_at INTEGER NOT NULL,
                    PRIMARY KEY(provider_id, channel_id)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_external_audio_sources_provider_id ON external_audio_sources(provider_id)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_external_audio_sources_provider_id_stream_id ON external_audio_sources(provider_id, stream_id)")
        }
    }
}
