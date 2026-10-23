package com.hebi.quickcut.media

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExoPlayerAssetLoader
import java.util.concurrent.Executor

@OptIn(UnstableApi::class)
internal class GuardedVideoFrameProcessorFactory(
    private val guard: ExactExportFrameGuard,
) : VideoFrameProcessor.Factory {
    private val factory = DefaultVideoFrameProcessor.Factory.Builder().build()

    override fun create(
        context: Context,
        debugViewProvider: DebugViewProvider,
        outputColorInfo: ColorInfo,
        renderFramesAutomatically: Boolean,
        listenerExecutor: Executor,
        listener: VideoFrameProcessor.Listener,
    ): VideoFrameProcessor {
        val processor = factory.create(context, debugViewProvider, outputColorInfo,
            renderFramesAutomatically, listenerExecutor, listener)
        val registration = guard.beginPipeline()
        return object : VideoFrameProcessor by processor {
            override fun registerInputFrame(): Boolean =
                processor.registerInputFrame().also(registration::registered)
        }
    }
}

/** A bounded retry only: keep the default audio selection and restrict video to software codecs.
 * An unavailable software decoder fails before publication. */
@OptIn(UnstableApi::class)
internal fun softwareVideoAssetLoaderFactory(context: Context): AssetLoader.Factory {
    val decoder = DefaultDecoderFactory.Builder(context)
        .setMediaCodecSelector { mime, secure, tunneling ->
            val available = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            if (mime.startsWith("video/")) available.filter {
                isSoftwareVideoDecoder(Build.VERSION.SDK_INT, it.name, it.softwareOnly,
                    it.hardwareAccelerated, it.vendor)
            }.also { software ->
                Log.i("QuickCutExport", "exact_retry_video_mime=$mime candidates=" +
                    available.joinToString(",", limit = 16) {
                        "${it.name}[software=${it.softwareOnly},hardware=${it.hardwareAccelerated},vendor=${it.vendor}]"
                    } + " software_candidates=" +
                    software.joinToString(",") { it.name })
            } else available
        }
        .setListener { name, _ -> Log.i("QuickCutExport", "exact_retry_decoder=$name") }
        .build()
    return ExoPlayerAssetLoader.Factory(context, decoder, Clock.DEFAULT)
}
