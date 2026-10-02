package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ExternalAudioSource
import com.streamvault.domain.repository.ChannelRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * In-memory view of the persistent External Audio library.
 *
 * The persistent source of truth is Room, prepared once after Live TV sync. The memory cache
 * is populated from Room and never resolves or refreshes a stream URL.
 */
@Singleton
class AudioSourceCatalogCache @Inject constructor(
    private val channelRepository: ChannelRepository,
) {
    private val cache = ConcurrentHashMap<Long, List<Channel>>()
    private val preparedCache = ConcurrentHashMap<Long, Map<Long, ExternalAudioSource>>()

    suspend fun warm(providerId: Long): List<Channel> {
        if (providerId <= 0L) return emptyList()
        val prepared = channelRepository.getExternalAudioSources(providerId)
        val preparedByChannel = prepared.associateBy { it.channelId }
        preparedCache[providerId] = preparedByChannel

        // Build the picker directly from the persistent Audio library. Do not wait for
        // the Live TV Flow here: the Audio library is already prepared in Room.
        val channels = prepared
            .asSequence()
            .filter { it.sourceUrl.isNotBlank() || it.resolvedUrl.isNotBlank() }
            .map { source ->
                Channel(
                    id = source.channelId,
                    name = source.name,
                    canonicalName = source.name,
                    logoUrl = source.logoUrl,
                    groupTitle = source.groupTitle,
                    streamUrl = source.sourceUrl.ifBlank { source.resolvedUrl },
                    providerId = source.providerId,
                    streamId = source.streamId
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()

        cache[providerId] = channels
        return channels
    }

    fun get(providerId: Long): List<Channel>? = cache[providerId]

    fun getPreparedSources(providerId: Long): List<ExternalAudioSource> =
        preparedCache[providerId]
            ?.values
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .orEmpty()

    fun getPrepared(providerId: Long, channelId: Long): ExternalAudioSource? =
        preparedCache[providerId]?.get(channelId)

    suspend fun refreshPrepared(providerId: Long, channelId: Long): ExternalAudioSource? {
        val refreshed = channelRepository.refreshExternalAudioSource(providerId, channelId) ?: return null
        preparedCache.compute(providerId) { _, current ->
            (current ?: emptyMap()) + (channelId to refreshed)
        }
        return refreshed
    }

    fun wasWarmed(providerId: Long): Boolean =
        providerId > 0L && cache.containsKey(providerId)

    fun clear(providerId: Long? = null) {
        if (providerId == null) {
            cache.clear()
            preparedCache.clear()
        } else {
            cache.remove(providerId)
            preparedCache.remove(providerId)
        }
    }
}
