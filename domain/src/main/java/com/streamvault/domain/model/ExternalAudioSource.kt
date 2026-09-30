package com.streamvault.domain.model

/**
 * Persisted External Audio source prepared from the provider's Live TV catalog.
 *
 * The source identity remains tied to the Live TV channel while the resolved playback
 * information is cached locally so selecting Audio Source does not perform provider
 * resolution again unless the cached URL has expired.
 */
data class ExternalAudioSource(
    val providerId: Long,
    val channelId: Long,
    val streamId: Long,
    val name: String,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val sourceUrl: String,
    val resolvedUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val expirationTime: Long? = null,
    val containerExtension: String? = null,
    val preparedAt: Long = System.currentTimeMillis(),
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expirationTime?.let { it > 0L && it <= now + EXPIRY_SAFETY_WINDOW_MS } == true

    companion object {
        private const val EXPIRY_SAFETY_WINDOW_MS = 30_000L
    }
}
