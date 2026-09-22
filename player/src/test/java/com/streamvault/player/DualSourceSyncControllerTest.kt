package com.streamvault.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DualSourceSyncControllerTest {
    private val controller = DualSourceSyncController()
    @Test fun liveWallClocksCalculateAudioMinusVideoDrift() {
        val video = PlaybackClockSnapshot(1000, true, 3000, 10000, true)
        val audio = PlaybackClockSnapshot(1200, true, 2800, 10180, true)
        assertThat(controller.calculateDriftMs(video, audio)).isEqualTo(180)
    }
    @Test fun smallDriftIsStable() {
        assertThat(controller.evaluate(clock(10000), clock(10100))).isEqualTo(SyncDecision.Stable(100))
    }
    @Test fun mediumDriftRequestsSoftCorrection() {
        assertThat(controller.evaluate(clock(10000), clock(10400))).isEqualTo(SyncDecision.SoftCorrect(400))
    }
    @Test fun largeDriftRequestsHardResync() {
        assertThat(controller.evaluate(clock(10000), clock(11000))).isEqualTo(SyncDecision.HardResync(1000))
    }
    @Test fun missingComparableClockDoesNotGuess() {
        val video = PlaybackClockSnapshot(1000, true, null, null, true)
        val audio = PlaybackClockSnapshot(1200, true, null, null, true)
        assertThat(controller.evaluate(video, audio)).isEqualTo(SyncDecision.NoClock)
    }
    private fun clock(positionMs: Long) = PlaybackClockSnapshot(positionMs, false, null, null, true)
}
