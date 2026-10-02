package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.repository.ChannelRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Audio-source catalog is derived from the already persisted Live TV catalog.
 *
 * No name, group, type, quality, sports, whitelist, or blacklist filtering is applied here.
 * Room's channels table remains the persistent source of truth, so after an app restart the
 * first read is local and does not re-download the playlist.
 */
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

        val channels = channelRepository.getChannels(providerId)
            .first()
            .asSequence()
            .filter { it.streamUrl.isNotBlank() }
            .distinctBy { it.streamUrl }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()

        cache[providerId] = channels
        return channels
    }

    fun get(providerId: Long): List<Channel>? = cache[providerId]

    /**
     * True only when the complete unfiltered catalog is already in process memory.
     * After process death warm() reads the same persistent Room catalog locally.
     */
    fun wasWarmed(providerId: Long): Boolean =
        providerId > 0L && cache.containsKey(providerId)

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
