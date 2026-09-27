package com.streamvault.app.activation

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ManagedVideoConfig(val serverUrl: String, val username: String, val password: String)
data class ManagedAudioConfig(val m3uUrl: String? = null)
data class ManagedActivationConfig(val expiresAt: String?, val video: ManagedVideoConfig?, val audio: ManagedAudioConfig?)

@Singleton
class ActivationConfigStore @Inject constructor() {
    private val _config = MutableStateFlow<ManagedActivationConfig?>(null)
    val config: StateFlow<ManagedActivationConfig?> = _config.asStateFlow()
    fun set(config: ManagedActivationConfig) { _config.value = config }
    fun clear() { _config.value = null }
}