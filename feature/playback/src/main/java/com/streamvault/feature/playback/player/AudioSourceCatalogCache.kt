package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.repository.ChannelRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class AudioSourceCatalogCache @Inject constructor(
    private val channelRepository: ChannelRepository,
) {
    private val cache = mutableMapOf<Long, List<Channel>>()
    private val locks = mutableMapOf<Long, Any>()

    suspend fun warm(providerId: Long): List<Channel> {
        if (providerId <= 0L) return emptyList()
        cache[providerId]?.let { return it }

        val lock = synchronized(locks) { locks.getOrPut(providerId) { Any() } }
        return synchronized(lock) {
            cache[providerId]
        } ?: channelRepository.getChannels(providerId).first().also { channels ->
            synchronized(lock) {
                cache.putIfAbsent(providerId, channels)
            }
        }
    }

    fun get(providerId: Long): List<Channel>? = cache[providerId]

    fun clear(providerId: Long? = null) {
        if (providerId == null) cache.clear() else cache.remove(providerId)
    }
}
