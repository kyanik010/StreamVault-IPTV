package com.streamvault.player

import com.streamvault.domain.model.StreamInfo
import javax.inject.Inject

class DualSourcePlaybackController @Inject constructor(
    private val factory: PlayerEngineFactory
) {
    private var video: PlayerEngine? = null
    private var audio: PlayerEngine? = null

    fun start(videoStream: StreamInfo, audioStream: StreamInfo?) {
        stop()
        video = factory.create().also {
            it.setAudioOnlyMode(false)
            it.prepare(videoStream, autoPlay = true)
        }
        if (audioStream != null) {
            audio = factory.create().also {
                it.setAudioOnlyMode(true)
                it.setMediaSessionEnabled(false)
                it.setAudioFocusBypassed(true)
                it.prepare(audioStream, autoPlay = false)
            }
        }
    }

    fun startAudio() {
        audio?.play()
    }

    fun stop() {
        audio?.release()
        video?.release()
        audio = null
        video = null
    }

    fun videoEngine(): PlayerEngine? = video
    fun audioEngine(): PlayerEngine? = audio
}
