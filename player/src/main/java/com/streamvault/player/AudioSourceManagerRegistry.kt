package com.streamvault.player

object AudioSourceManagerRegistry {
    @Volatile
    private var instance: AudioSourceManager? = null

    fun install(manager: AudioSourceManager) {
        instance = manager
    }

    fun get(): AudioSourceManager =
        instance ?: error("AudioSourceManager has not been installed")
}
