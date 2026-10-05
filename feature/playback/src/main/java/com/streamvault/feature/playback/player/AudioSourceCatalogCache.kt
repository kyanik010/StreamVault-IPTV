package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight in-memory cache for the managed external-audio channel list.
 *
 * External Audio is loaded through the StreamVault Audio Source plugin. This
 * cache deliberately does not access the normal ChannelRepository or Xtream
 * provider, so the dedicated audio M3U remains isolated from video sources.
 */
@Singleton
class AudioSourceCatalogCache @Inject constructor() {
    private val cache = ConcurrentHashMap<Long, List<Channel>>()

    fun put(providerId: Long, channels: List<Channel>) {
        if (providerId > 0L) cache[providerId] = channels
    }

    fun get(providerId: Long): List<Channel>? = cache[providerId]

    fun wasWarmed(providerId: Long): Boolean =
        providerId > 0L && cache.containsKey(providerId)

    fun clear(providerId: Long? = null) {
        if (providerId == null) cache.clear()
        else cache.remove(providerId)
    }
}
