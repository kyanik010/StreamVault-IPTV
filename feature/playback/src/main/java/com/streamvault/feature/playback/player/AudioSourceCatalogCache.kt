package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.repository.ChannelRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class AudioSourceCatalogCache @Inject constructor(
    private val channelRepository: ChannelRepository,
) {
    private val cache = ConcurrentHashMap<Long, List<Channel>>()
    private val locks = ConcurrentHashMap<Long, Any>()

    suspend fun warm(providerId: Long): List<Channel> {
        if (providerId <= 0L) return emptyList()
        cache[providerId]?.let { return it }

        val lock = locks.computeIfAbsent(providerId) { Any() }
        synchronized(lock) {
            cache[providerId]?.let { return it }
        }

        val channels = channelRepository.getChannels(providerId).first()
        return cache.putIfAbsent(providerId, channels) ?: channels
    }

    fun get(providerId: Long): List<Channel>? = cache[providerId]

    fun clear(providerId: Long? = null) {
        if (providerId == null) {
            cache.clear()
            locks.clear()
        } else {
            cache.remove(providerId)
            locks.remove(providerId)
        }
    }
}
