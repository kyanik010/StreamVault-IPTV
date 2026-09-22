package com.streamvault.player

import com.streamvault.domain.model.StreamInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

class DualSourcePlaybackController @Inject constructor(
    private val factory: PlayerEngineFactory,
    private val syncController: DualSourceSyncController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null
    private var video: PlayerEngine? = null
    private var audio: PlayerEngine? = null
    private var videoStream: StreamInfo? = null
    private var audioStream: StreamInfo? = null
    private var ownsVideo = false
    private var audioStarted = false
    private var sessionGeneration = 0L
    private var lastHardResyncAtMs = 0L
    private var nextAudioReconnectAtMs = 0L
    private var audioReconnectAttempt = 0
    private var nextVideoReconnectAtMs = 0L
    private var videoReconnectAttempt = 0
    private var manualOffsetMs = 0L

    private val _state = MutableStateFlow(DualSourcePlaybackState())
    val state: StateFlow<DualSourcePlaybackState> = _state.asStateFlow()

    fun start(videoStream: StreamInfo, audioStream: StreamInfo?) {
        stop()
        ownsVideo = true
        this.videoStream = videoStream
        this.audioStream = audioStream
        video = factory.create().also {
            it.setAudioOnlyMode(false)
            it.prepare(videoStream, autoPlay = true)
        }
        if (audioStream != null) {
            sessionGeneration++
            startMonitor(sessionGeneration)
        } else publishState()
    }

    fun attachAudio(videoEngine: PlayerEngine, videoStream: StreamInfo, audioStream: StreamInfo) {
        stopAudioOnly()
        sessionGeneration++
        val generation = sessionGeneration
        ownsVideo = false
        video = videoEngine
        this.videoStream = videoStream
        this.audioStream = audioStream
        audioStarted = false
        resetReconnectState()
        audio = factory.create().also {
            it.setAudioOnlyMode(true)
            it.setMediaSessionEnabled(false)
            it.setAudioFocusBypassed(true)
            it.setPlaybackSpeed(1f)
            it.prepare(audioStream, autoPlay = false)
        }
        publishState()
        startMonitor(generation)
    }

    fun stopAudioOnly() {
        sessionGeneration++
        monitorJob?.cancel()
        monitorJob = null
        audio?.setPlaybackSpeed(1f)
        audio?.release()
        audio = null
        audioStream = null
        audioStarted = false
        resetReconnectState()
        _state.value = _state.value.copy(
            audio = PlaybackState.IDLE,
            audioAttached = false,
            driftMs = null,
            syncState = DualSourceSyncState.IDLE,
            reconnectingAudio = false
        )
    }

    fun startAudio() {
        val engine = audio ?: return
        if (engine.playbackState.value != PlaybackState.READY) return
        audioStarted = true
        engine.setPlaybackSpeed(1f)
        engine.play()
        synchronize(forceHard = true)
    }

    fun syncNow() {
        if (audio?.playbackState?.value != PlaybackState.READY ||
            video?.playbackState?.value != PlaybackState.READY) return
        synchronize(forceHard = true)
    }

    fun setManualOffsetMs(offsetMs: Long) {
        manualOffsetMs = offsetMs.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
        synchronize(forceHard = false)
    }

    fun adjustManualOffsetMs(deltaMs: Long) {
        setManualOffsetMs(manualOffsetMs + deltaMs)
    }

    fun resetManualOffset() {
        setManualOffsetMs(0L)
        synchronize(forceHard = true)
    }

    fun manualOffsetMs(): Long = manualOffsetMs

    fun updateVideoStream(videoStream: StreamInfo) {
        this.videoStream = videoStream
        if (audio != null) synchronize(forceHard = true)
    }

    private fun startMonitor(generation: Long) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive && generation == sessionGeneration) {
                val a = audio
                val v = video
                if (a != null && v != null && generation == sessionGeneration) {
                    if (!audioStarted &&
                        a.playbackState.value == PlaybackState.READY &&
                        v.playbackState.value == PlaybackState.READY
                    ) {
                        startAudio()
                    } else if (audioStarted) {
                        recoverIfNeeded(generation, v, a)
                        synchronize(forceHard = false)
                    }
                    publishState()
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private fun recoverIfNeeded(generation: Long, v: PlayerEngine, a: PlayerEngine) {
        val now = System.currentTimeMillis()
        if (a.playbackState.value == PlaybackState.READY) {
            audioReconnectAttempt = 0
            nextAudioReconnectAtMs = 0L
        } else if (a.playbackState.value == PlaybackState.ERROR &&
            generation == sessionGeneration && now >= nextAudioReconnectAtMs
        ) {
            val stream = audioStream ?: return
            audioStarted = false
            audioReconnectAttempt++
            nextAudioReconnectAtMs = now + reconnectDelayMs(audioReconnectAttempt)
            a.prepare(stream, autoPlay = false)
        }

        if (ownsVideo && v.playbackState.value == PlaybackState.ERROR &&
            generation == sessionGeneration && now >= nextVideoReconnectAtMs
        ) {
            val stream = videoStream ?: return
            videoReconnectAttempt++
            nextVideoReconnectAtMs = now + reconnectDelayMs(videoReconnectAttempt)
            v.prepare(stream, autoPlay = true)
        }
    }

    private fun synchronize(forceHard: Boolean) {
        val v = video ?: return
        val a = audio ?: return
        val decision = syncController.evaluate(
            v.clockSnapshot(), a.clockSnapshot(), manualOffsetMs
        )
        when (decision) {
            is SyncDecision.Stable -> {
                a.setPlaybackSpeed(1f)
                setSyncState(DualSourceSyncState.SYNCHRONIZED, decision.driftMs)
            }
            is SyncDecision.SoftCorrect -> {
                a.setPlaybackSpeed(if (decision.driftMs > 0) SOFT_SLOW_SPEED else SOFT_FAST_SPEED)
                setSyncState(DualSourceSyncState.SOFT_CORRECTING, decision.driftMs)
            }
            is SyncDecision.HardResync -> {
                if (forceHard || System.currentTimeMillis() - lastHardResyncAtMs >= HARD_RESYNC_COOLDOWN_MS) {
                    applyHardResync(a, decision.driftMs)
                    lastHardResyncAtMs = System.currentTimeMillis()
                }
                setSyncState(DualSourceSyncState.HARD_RESYNC, decision.driftMs)
            }
            SyncDecision.NoClock -> setSyncState(DualSourceSyncState.WAITING_FOR_VIDEO, null)
        }
    }

    private fun applyHardResync(audioEngine: PlayerEngine, driftMs: Long) {
        audioEngine.setPlaybackSpeed(1f)
        val snapshot = audioEngine.clockSnapshot()
        val stream = audioStream
        if (snapshot.isLive && stream != null) {
            audioStarted = false
            audioEngine.prepare(stream, autoPlay = true)
            audioStarted = true
        } else {
            val target = max(0L, audioEngine.currentPosition.value - driftMs)
            audioEngine.seekTo(target)
            if (audioStarted) audioEngine.play()
        }
    }

    private fun setSyncState(state: DualSourceSyncState, driftMs: Long?) {
        _state.value = _state.value.copy(syncState = state, driftMs = driftMs)
    }

    private fun publishState() {
        val v = video
        val a = audio
        _state.value = _state.value.copy(
            video = v?.playbackState?.value ?: PlaybackState.IDLE,
            audio = a?.playbackState?.value ?: PlaybackState.IDLE,
            audioAttached = a != null,
            reconnectingAudio = a?.playbackState?.value == PlaybackState.ERROR ||
                nextAudioReconnectAtMs > System.currentTimeMillis(),
            reconnectingVideo = ownsVideo && v?.playbackState?.value == PlaybackState.ERROR
        )
    }

    private fun resetReconnectState() {
        audioReconnectAttempt = 0
        nextAudioReconnectAtMs = 0L
        videoReconnectAttempt = 0
        nextVideoReconnectAtMs = 0L
    }

    private fun reconnectDelayMs(attempt: Int): Long =
        (1_000L * (1L shl (attempt - 1).coerceIn(0, 4))).coerceAtMost(MAX_RECONNECT_DELAY_MS)

    fun stop() {
        sessionGeneration++
        monitorJob?.cancel()
        monitorJob = null
        audio?.setPlaybackSpeed(1f)
        audio?.release()
        if (ownsVideo) video?.release()
        audio = null
        if (ownsVideo) video = null
        videoStream = null
        audioStream = null
        audioStarted = false
        ownsVideo = false
        resetReconnectState()
        _state.value = DualSourcePlaybackState()
    }

    fun videoEngine(): PlayerEngine? = video
    fun audioEngine(): PlayerEngine? = audio

    companion object {
        const val SYNC_INTERVAL_MS = 500L
        const val HARD_RESYNC_COOLDOWN_MS = 2_000L
        const val SOFT_SLOW_SPEED = 0.995f
        const val SOFT_FAST_SPEED = 1.005f
        const val MIN_OFFSET_MS = -2_000L
        const val MAX_OFFSET_MS = 2_000L
        const val OFFSET_STEP_MS = 50L
        const val MAX_RECONNECT_DELAY_MS = 16_000L
    }
}
