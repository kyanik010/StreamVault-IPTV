package com.streamvault.player

import com.streamvault.domain.model.StreamInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

/**
 * Owns two independent player engines and coordinates their lifecycle.
 *
 * The video engine is never used as the audio engine. The audio engine is configured
 * as audio-only and has independent media-session/audio-focus ownership.
 */
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
    private var audioStarted = false
    private var lastHardResyncAtMs = 0L

    fun start(videoStream: StreamInfo, audioStream: StreamInfo?) {
        stop()
        this.videoStream = videoStream
        this.audioStream = audioStream

        video = factory.create().also {
            it.setAudioOnlyMode(false)
            it.prepare(videoStream, autoPlay = true)
        }

        if (audioStream != null) {
            audio = factory.create().also {
                it.setAudioOnlyMode(true)
                it.setMediaSessionEnabled(false)
                it.setAudioFocusBypassed(true)
                it.setPlaybackSpeed(1f)
                it.prepare(audioStream, autoPlay = false)
            }
            startMonitor()
        }
    }

    fun startAudio() {
        val engine = audio ?: return
        audioStarted = true
        engine.setPlaybackSpeed(1f)
        engine.play()
        synchronize(forceHard = true)
    }

    fun syncNow() {
        synchronize(forceHard = true)
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                val a = audio
                val v = video
                if (a != null && v != null) {
                    if (!audioStarted &&
                        a.playbackState.value == PlaybackState.READY &&
                        v.playbackState.value == PlaybackState.READY
                    ) {
                        startAudio()
                    } else if (audioStarted) {
                        recoverIfNeeded(v, a)
                        synchronize(forceHard = false)
                    }
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private fun recoverIfNeeded(v: PlayerEngine, a: PlayerEngine) {
        if (a.playbackState.value == PlaybackState.ERROR) {
            audioStream?.let {
                audioStarted = false
                a.prepare(it, autoPlay = false)
            }
            return
        }
        if (v.playbackState.value == PlaybackState.ERROR) {
            videoStream?.let { v.prepare(it, autoPlay = true) }
        }
    }

    private fun synchronize(forceHard: Boolean) {
        val v = video ?: return
        val a = audio ?: return
        val decision = syncController.evaluate(v.clockSnapshot(), a.clockSnapshot())
        when (decision) {
            is SyncDecision.Stable -> a.setPlaybackSpeed(1f)
            is SyncDecision.SoftCorrect -> {
                // Temporarily bias the audio clock toward video without interrupting video.
                a.setPlaybackSpeed(if (decision.driftMs > 0) SOFT_SLOW_SPEED else SOFT_FAST_SPEED)
            }
            is SyncDecision.HardResync -> {
                if (forceHard || System.currentTimeMillis() - lastHardResyncAtMs >= HARD_RESYNC_COOLDOWN_MS) {
                    applyHardResync(a, decision.driftMs)
                    lastHardResyncAtMs = System.currentTimeMillis()
                }
            }
            SyncDecision.NoClock -> Unit
        }
    }

    private fun applyHardResync(audioEngine: PlayerEngine, driftMs: Long) {
        audioEngine.setPlaybackSpeed(1f)
        val target = max(0L, audioEngine.currentPosition.value - driftMs)
        audioEngine.seekTo(target)
        if (audioStarted) audioEngine.play()
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        audio?.setPlaybackSpeed(1f)
        audio?.release()
        video?.release()
        audio = null
        video = null
        videoStream = null
        audioStream = null
        audioStarted = false
        lastHardResyncAtMs = 0L
    }

    fun videoEngine(): PlayerEngine? = video
    fun audioEngine(): PlayerEngine? = audio

    companion object {
        const val SYNC_INTERVAL_MS = 500L
        const val HARD_RESYNC_COOLDOWN_MS = 2_000L
        const val SOFT_SLOW_SPEED = 0.995f
        const val SOFT_FAST_SPEED = 1.005f
    }
}
