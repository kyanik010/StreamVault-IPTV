package com.streamvault.feature.settings.api

interface SettingsSurfaceRefreshPort {
    suspend fun refreshWatchNext()
    suspend fun refreshRecommendations()
    suspend fun refreshTvInputCatalog()
    fun enqueueTvInputCatalogRefresh()
    suspend fun syncExternalAudio(): com.streamvault.domain.model.Result<Unit>
}
