package com.hebi.quickcut.media

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Counts frames after Media3 has excluded decoder preroll and frames outside the requested cut. */
internal class ExactExportFrameGuard {
    @Volatile private var currentPipeline: Registration? = null

    /** Our composition has one video sequence; optimization restarts replace its discarded graph. */
    fun beginPipeline(): Registration = Registration().also { currentPipeline = it }

    fun verify(outputFrames: Int, fullyTranscoded: Boolean) {
        // A copy/optimized mixed export also muxes frames which never enter the decoded graph.
        if (!fullyTranscoded) return
        val expected = currentPipeline?.count
        if (expected == null || expected != outputFrames.toLong()) {
            throw ExactExportFrameCountException(expected, outputFrames)
        }
    }

    class Registration {
        private val accepted = AtomicLong()
        val count: Long get() = accepted.get()
        fun registered(wasAccepted: Boolean) {
            if (wasAccepted) accepted.incrementAndGet()
        }
    }
}

internal class ExactExportFrameCountException(
    val expectedFrames: Long?,
    val outputFrames: Int,
) : IOException("영상 프레임을 모두 처리하지 못해 저장을 중단했습니다. 다시 시도해 주세요.") {
    val hasMissingFrames: Boolean
        get() = expectedFrames != null && outputFrames.toLong() < expectedFrames
}

/** Retry only observed frame loss, once. The caller uses a fresh graph and software video decoder. */
internal suspend fun <T> recoverExactExportFrames(export: suspend (softwareVideo: Boolean) -> T): T {
    return try {
        export(false)
    } catch (first: ExactExportFrameCountException) {
        if (!first.hasMissingFrames) throw first
        try {
            export(true)
        } catch (retryFailure: Exception) {
            if (retryFailure !== first) retryFailure.addSuppressed(first)
            throw retryFailure
        }
    }
}
