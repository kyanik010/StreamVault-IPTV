package com.streamvault.feature.playback.player

import android.content.Context
import com.streamvault.domain.model.Channel
import com.streamvault.domain.repository.ChannelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class AudioSourceCatalogCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val channelRepository: ChannelRepository,
) {
    private val cache = ConcurrentHashMap<Long, List<Channel>>()
    private val locks = ConcurrentHashMap<Long, Any>()
    private val prefs by lazy {
        context.getSharedPreferences("streamvault_audio_catalog_cache", Context.MODE_PRIVATE)
    }

    suspend fun warm(providerId: Long): List<Channel> {
        if (providerId <= 0L) return emptyList()
        cache[providerId]?.let { return it }

        val lock = locks.computeIfAbsent(providerId) { Any() }
        synchronized(lock) {
            cache[providerId]?.let { return it }
        }

        val channels = channelRepository.getChannels(providerId).first()
        cache[providerId] = channels
        if (channels.isNotEmpty()) {
            prefs.edit().putBoolean(warmedKey(providerId), true).apply()
        }
        return channels
    }

    fun get(providerId: Long): List<Channel>? = cache[providerId]

    fun wasWarmed(providerId: Long): Boolean =
        providerId > 0L && prefs.getBoolean(warmedKey(providerId), false)

    fun clear(providerId: Long? = null) {
        if (providerId == null) {
            cache.clear()
            locks.clear()
            prefs.edit().clear().apply()
        } else {
            cache.remove(providerId)
            locks.remove(providerId)
            prefs.edit().remove(warmedKey(providerId)).apply()
        }
    }

    private fun warmedKey(providerId: Long): String = "provider_${providerId}_warmed"
}
