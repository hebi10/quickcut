package com.hebi.quickcut.media

import android.media.metrics.LogSessionId
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Codec

internal class EncodingStageTracker(private val onStage: (ExportStage) -> Unit) {
    private var video = false
    private var audio = false

    @Synchronized fun videoStarted() {
        if (!video) { video = true; onStage(ExportStage.VIDEO_ENCODING) }
    }

    @Synchronized fun audioStarted() {
        if (!video && !audio) { audio = true; onStage(ExportStage.AUDIO_ENCODING) }
    }
}

@OptIn(UnstableApi::class, ExperimentalApi::class)
internal class TrackingEncoderFactory(
    private val delegate: Codec.EncoderFactory,
    onStage: (ExportStage) -> Unit,
) : Codec.EncoderFactory by delegate {
    private val tracker = EncodingStageTracker(onStage)

    override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec =
        delegate.createForAudioEncoding(format, logSessionId).also { tracker.audioStarted() }

    override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec =
        delegate.createForVideoEncoding(format, logSessionId).also { tracker.videoStarted() }
}
