package com.streamvault.app.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ExternalAudioSource
import com.streamvault.feature.playback.player.AudioSourceCatalogCache
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AudioLibraryUiState(
    val providerId: Long? = null,
    val sources: List<ExternalAudioSource> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class AudioLibraryViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val channelRepository: ChannelRepository,
    private val audioSourceCatalogCache: AudioSourceCatalogCache,
) : ViewModel() {
    private val _state = MutableStateFlow(AudioLibraryUiState())
    val state: StateFlow<AudioLibraryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            providerRepository.getActiveProvider().collectLatest { provider ->
                val id = provider?.id
                if (id == null || id <= 0L) {
                    _state.value = AudioLibraryUiState(
                        providerId = null,
                        loading = false,
                        error = "لا يوجد اشتراك IPTV نشط."
                    )
                    return@collectLatest
                }

                _state.value = AudioLibraryUiState(providerId = id, loading = true)
                runCatching {
                    // Audio Library and the in-player picker consume the same cache backed by
                    // the same persistent Room ExternalAudioSource records. This is a local read;
                    // it never prepares, resolves, refreshes, or contacts Xtream.
                    val cached = audioSourceCatalogCache.get(id)
                    if (cached == null) {
                        audioSourceCatalogCache.warm(id)
                    }
                    channelRepository.getExternalAudioSources(id)
                }.onSuccess { sources ->
                    _state.value = AudioLibraryUiState(
                        providerId = id,
                        sources = sources,
                        loading = false,
                        error = if (sources.isEmpty()) {
                            "مكتبة Audio لم تُجهز بعد. نفّذ مزامنة الاشتراك مرة واحدة."
                        } else null
                    )
                }.onFailure { error ->
                    _state.value = AudioLibraryUiState(
                        providerId = id,
                        loading = false,
                        error = error.message ?: "تعذر تحميل مكتبة Audio."
                    )
                }
            }
        }
    }
}
