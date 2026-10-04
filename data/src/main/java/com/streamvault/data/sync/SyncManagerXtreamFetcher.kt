package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamLiveStreamRow
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.util.runSuspendCatching
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Series
import kotlin.system.measureTimeMillis

private const val XTREAM_FETCHER_TAG = "SyncManager"

internal class SyncManagerXtreamFetcher(
    private val xtreamCatalogApiService: XtreamApiService,
    private val xtreamCatalogHttpService: OkHttpXtreamApiService,
    private val xtreamSupport: SyncManagerXtreamSupport,
    private val sanitizeThrowableMessage: (Throwable?) -> String
) {
    suspend fun fetchLiveCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory,
        stageBatchSize: Int? = null,
        onMappedBatch: (suspend (List<Channel>) -> Unit)? = null
    ): TimedCategoryOutcome<Channel> {
        val endpoint = XtreamUrlFactory.buildPlayerApiUrl(
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            action = "get_live_streams",
            extraQueryParams = mapOf("category_id" to category.categoryId)
        )
        var mappedChannels: List<Channel> = emptyList()
        var rawCount = 0
        var mappedCount = 0
        var categoryFailure: Throwable? = null
        val streamingStageBatchSize = stageBatchSize?.takeIf { it > 0 }

        suspend fun emitMappedChannels(channels: List<Channel>) {
            if (channels.isEmpty()) return
            mappedCount += channels.size
            onMappedBatch?.invoke(channels)
            if (onMappedBatch == null) {
                mappedChannels = mappedChannels + channels
            }
        }

        suspend fun streamThinRowsInBatches(): Triple<Int, List<Channel>, Int> {
            val batchSize = streamingStageBatchSize
            if (batchSize == null || onMappedBatch == null) {
                val rows = ArrayList<XtreamLiveStreamRow>()
                val streamedCount = xtreamCatalogHttpService.streamLiveStreamRows(endpoint) { row -> rows += row }
                val mapped = api.mapLiveStreamRowsSequence(rows.asSequence()).toList()
                return Triple(streamedCount, mapped, mapped.size)
            }

            val rawBatch = ArrayList<XtreamLiveStreamRow>(batchSize)
            var streamedCount = 0
            var mappedCountInBatch = 0

            suspend fun flushRawBatch() {
                if (rawBatch.isEmpty()) return
                val mapped = api.mapLiveStreamRowsSequence(rawBatch.asSequence()).toList()
                mappedCountInBatch += mapped.size
                emitMappedChannels(mapped)
                rawBatch.clear()
            }

            xtreamCatalogHttpService.streamLiveStreamRows(endpoint) { row ->
                rawBatch += row
                streamedCount++
                if (rawBatch.size >= batchSize) {
                    flushRawBatch()
                }
            }
            flushRawBatch()
            return Triple(streamedCount, emptyList(), mappedCountInBatch)
        }

        val elapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                    xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
                        val thinResult = runSuspendCatching {
                            streamThinRowsInBatches()
                        }
                        val thinValue = thinResult.getOrNull()
                        val thinCount = thinValue?.first ?: 0
                        val thinChannels = thinValue?.second.orEmpty()
                        val thinMappedCount = thinValue?.third ?: 0
                        val thinProducedUsableItems = thinMappedCount > 0 || thinChannels.isNotEmpty()
                        if (thinResult.isSuccess && (thinCount == 0 || thinProducedUsableItems)) {
                            rawCount = thinCount
                            mappedChannels = thinChannels
                        } else {
                            thinResult.exceptionOrNull()?.let { error ->
                                Log.w(
                                    XTREAM_FETCHER_TAG,
                                    "Xtream live category '${category.categoryName}' thin decode failed; retrying legacy decode: ${sanitizeThrowableMessage(error)}"
                                )
                            } ?: run {
                                Log.w(
                                    XTREAM_FETCHER_TAG,
                                    "Xtream live category '${category.categoryName}' thin decode returned $thinCount raw items but mapped none; retrying legacy decode."
                                )
                            }
                            val legacyStreams = xtreamCatalogApiService.getLiveStreams(endpoint)
                            rawCount = legacyStreams.size
                            emitMappedChannels(api.mapLiveStreamsResponse(legacyStreams))
                        }
                    }
                }
            }) {
                is Attempt.Success -> Unit
                is Attempt.Failure -> categoryFailure = attempt.error
            }
        }
        val outcome = when {
            categoryFailure != null -> {
                Log.w(
                    XTREAM_FETCHER_TAG,
                    "Xtream live category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(categoryFailure)}"
                )
                CategoryFetchOutcome.Failure(category.categoryName, categoryFailure!!)
            }
            rawCount == 0 -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream live category '${category.categoryName}' completed in ${elapsedMs}ms with a valid empty result."
                )
                CategoryFetchOutcome.Empty(category.categoryName)
            }
            else -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream live category '${category.categoryName}' completed in ${elapsedMs}ms with $rawCount raw items."
                )
                CategoryFetchOutcome.Success(category.categoryName, mappedChannels, rawCount)
            }
        }
        return TimedCategoryOutcome(category, outcome, elapsedMs)
    }

    suspend fun fetchMovieCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Movie> {
        var rawStreams: List<XtreamStream> = emptyList()
        var categoryFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.withMovieRequestTimeout("movie category '${category.categoryName}'") {
                    xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                        xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
                            xtreamCatalogApiService.getVodStreams(
                                XtreamUrlFactory.buildPlayerApiUrl(
                                    serverUrl = provider.serverUrl,
                                    username = provider.username,
                                    password = provider.password,
                                    action = "get_vod_streams",
                                    extraQueryParams = mapOf("category_id" to category.categoryId)
                                )
                            )
                        }
                    }
                }
            }) {
                is Attempt.Success -> rawStreams = attempt.value
                is Attempt.Failure -> categoryFailure = attempt.error
            }
        }
        val outcome = when {
            categoryFailure != null -> {
                Log.w(
                    XTREAM_FETCHER_TAG,
                    "Xtream movie category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(categoryFailure)}"
                )
                CategoryFetchOutcome.Failure(category.categoryName, categoryFailure!!)
            }
            rawStreams.isEmpty() -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream movie category '${category.categoryName}' completed in ${elapsedMs}ms with a valid empty result."
                )
                CategoryFetchOutcome.Empty(category.categoryName)
            }
            else -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream movie category '${category.categoryName}' completed in ${elapsedMs}ms with ${rawStreams.size} raw items."
                )
                CategoryFetchOutcome.Success(category.categoryName, api.mapVodStreamsResponse(rawStreams), rawStreams.size)
            }
        }
        return TimedCategoryOutcome(category, outcome, elapsedMs)
    }

    suspend fun fetchSeriesCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Series> {
        var rawSeries: List<XtreamSeriesItem> = emptyList()
        var categoryFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.withSeriesRequestTimeout("series category '${category.categoryName}'") {
                    xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                        xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
                            xtreamCatalogApiService.getSeriesList(
                                XtreamUrlFactory.buildPlayerApiUrl(
                                    serverUrl = provider.serverUrl,
                                    username = provider.username,
                                    password = provider.password,
                                    action = "get_series",
                                    extraQueryParams = mapOf("category_id" to category.categoryId)
                                )
                            )
                        }
                    }
                }
            }) {
                is Attempt.Success -> rawSeries = attempt.value
                is Attempt.Failure -> categoryFailure = attempt.error
            }
        }
        val outcome = when {
            categoryFailure != null -> {
                Log.w(
                    XTREAM_FETCHER_TAG,
                    "Xtream series category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(categoryFailure)}"
                )
                CategoryFetchOutcome.Failure(category.categoryName, categoryFailure!!)
            }
            rawSeries.isEmpty() -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream series category '${category.categoryName}' completed in ${elapsedMs}ms with a valid empty result."
                )
                CategoryFetchOutcome.Empty(category.categoryName)
            }
            else -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream series category '${category.categoryName}' completed in ${elapsedMs}ms with ${rawSeries.size} raw items."
                )
                CategoryFetchOutcome.Success(category.categoryName, api.mapSeriesListResponse(rawSeries), rawSeries.size)
            }
        }
        return TimedCategoryOutcome(category, outcome, elapsedMs)
    }

}
