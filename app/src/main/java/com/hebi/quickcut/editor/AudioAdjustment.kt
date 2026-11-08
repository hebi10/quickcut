package com.hebi.quickcut.editor

import kotlin.math.min

data class AudioAdjustment(
    val volumePercent: Int = 100,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
) {
    init {
        require(volumePercent in 0..100) { "볼륨은 0~100% 사이여야 합니다." }
        require(fadeInMs >= 0L && fadeOutMs >= 0L) { "페이드 길이는 0 이상이어야 합니다." }
    }

    val isDefault: Boolean get() = volumePercent == 100 && fadeInMs == 0L && fadeOutMs == 0L

    fun bounded(durationMs: Long): AudioAdjustment {
        require(durationMs > 0L)
        return copy(fadeInMs = min(fadeInMs, durationMs), fadeOutMs = min(fadeOutMs, durationMs))
    }

    companion object {
        val DEFAULT = AudioAdjustment()
    }
}

/** Deterministic, sample-position-based envelope shared by preview and MP4 export. */
object AudioEnvelope {
    fun gainAtMs(adjustment: AudioAdjustment, durationMs: Long, positionMs: Double): Float {
        require(durationMs > 0L)
        if (adjustment.volumePercent == 0) return 0f
        val time = positionMs.coerceIn(0.0, durationMs.toDouble())
        val fadeIn = if (adjustment.fadeInMs > 0L) {
            (time / adjustment.fadeInMs).coerceIn(0.0, 1.0)
        } else 1.0
        val fadeOut = if (adjustment.fadeOutMs > 0L) {
            ((durationMs - time) / adjustment.fadeOutMs).coerceIn(0.0, 1.0)
        } else 1.0
        return (adjustment.volumePercent / 100.0 * min(fadeIn, fadeOut))
            .coerceIn(0.0, 1.0).toFloat()
    }

    fun gainAtSample(
        adjustment: AudioAdjustment,
        durationMs: Long,
        samplePosition: Long,
        sampleRate: Int,
    ): Float {
        require(samplePosition >= 0L && sampleRate > 0)
        val milliseconds = samplePosition.toDouble() * 1_000.0 / sampleRate
        return gainAtMs(adjustment, durationMs, milliseconds)
    }
}
