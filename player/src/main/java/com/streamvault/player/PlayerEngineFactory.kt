package com.streamvault.player

import javax.inject.Inject
import javax.inject.Provider

/**
 * Creates independent Media3 engines. Each call returns a distinct engine instance and therefore
 * a distinct ExoPlayer lifecycle.
 */
class PlayerEngineFactory @Inject constructor(
    private val provider: Provider<Media3PlayerEngine>
) {
    fun create(): PlayerEngine = provider.get()
}
