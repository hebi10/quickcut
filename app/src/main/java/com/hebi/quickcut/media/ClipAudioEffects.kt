package com.hebi.quickcut.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.UnstableApi
import com.hebi.quickcut.editor.AudioAdjustment
import com.hebi.quickcut.editor.AudioEnvelope
import com.hebi.quickcut.editor.VideoClip
import kotlin.math.ceil

/** Media3 PCM gain automation; each clip receives a separate processor instance. */
@OptIn(UnstableApi::class)
object ClipAudioEffects {
    fun gainProvider(adjustment: AudioAdjustment, durationMs: Long): GainProcessor.GainProvider {
        require(durationMs > 0L)
        val bounded = adjustment.bounded(durationMs)
        return object : GainProcessor.GainProvider {
            override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float =
                AudioEnvelope.gainAtSample(bounded, durationMs, samplePosition, sampleRate)

            override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long {
                if (bounded.volumePercent != 100 || sampleRate <= 0 || samplePosition < 0L) {
                    return C.TIME_UNSET
                }
                val firstUnity = ceil(bounded.fadeInMs.toDouble() * sampleRate / 1_000.0).toLong()
                val lastUnity = ((durationMs - bounded.fadeOutMs).toDouble() * sampleRate / 1_000.0)
                    .toLong()
                if (samplePosition < firstUnity || samplePosition >= lastUnity) return C.TIME_UNSET
                return if (bounded.fadeOutMs == 0L) C.TIME_END_OF_SOURCE else lastUnity
            }
        }
    }

    fun processors(clip: VideoClip): List<androidx.media3.common.audio.AudioProcessor> {
        if (!clip.hasAudio || clip.muted || clip.audio.isDefault) return emptyList()
        return listOf(GainProcessor(gainProvider(clip.audio, clip.durationMs)))
    }
}
