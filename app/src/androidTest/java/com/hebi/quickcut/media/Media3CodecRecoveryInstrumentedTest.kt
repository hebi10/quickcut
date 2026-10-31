package com.hebi.quickcut.media

import android.content.Context
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.metrics.LogSessionId
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4TimestampData
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Inject only the failure signal, after a real device encoder has allocated its codec. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class, ExperimentalApi::class)
class Media3CodecRecoveryInstrumentedTest {
    @Test fun allocatedCodecIsReleasedBeforeSoftwareRetryAndExactOutputRemainsPrivate() = runBlocking {
        withFixture { context, clip ->
            val original = clip.copy()
            val output = File(context.cacheDir, "codec_recovery_${UUID.randomUUID()}.mp4")
            val retryOutput = File(context.cacheDir, "codec_retry_${UUID.randomUUID()}.mp4")
            val timestamp = 1_600_000_000_000L
            val events = mutableListOf<String>()
            val failed = IllegalStateException("injected device codec processing failure")
            try {
                withTimeout(60_000L) {
                    recoverMedia3ExportFile(output, { retryOutput }, {
                        assertSame(failed, it)
                        ExportCodecRetryFacts(3002, videoDecoder = true)
                    }) { file, retry ->
                        val label = if (retry == null) "initial" else "retry"
                        if (retry != null) {
                            assertEquals(ExportRetryMode.SOFTWARE_VIDEO, retry)
                            assertEquals("released-initial", events.last())
                            assertFalse(output.exists())
                            assertNotEquals(output.absolutePath, file.absolutePath)
                        }
                        encode(context, clip, file, timestamp, retry != null,
                            injectedFailure = if (retry == null) failed else null,
                            onAllocated = { events += "allocated-$label" },
                            onReleased = { events += "released-$label" })
                    }
                }
                assertEquals(listOf("allocated-initial", "released-initial", "allocated-retry", "released-retry"), events)
                assertEquals(original, clip)
                assertFalse(retryOutput.exists())
                MediaReader.validateExport(output, clip.durationMs, expectAudio = clip.hasAudio && !clip.muted,
                    expectedTimestampMs = timestamp)
                assertTrue(output.length() > 0L)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(output.absolutePath)
                    val firstFrame = checkNotNull(retriever.getScaledFrameAtTime(
                        0L, MediaMetadataRetriever.OPTION_CLOSEST, 160, 90))
                    try {
                        val color = firstFrame.getPixel(firstFrame.width / 2, firstFrame.height / 2)
                        assertTrue("Software retry must retain red footage before the 2s sync boundary",
                            Color.red(color) > Color.blue(color) + 100)
                    } finally { firstFrame.recycle() }
                } finally { retriever.release() }
            } finally { output.delete(); retryOutput.delete() }
        }
    }

    @Test fun cancellationAfterCodecAllocationReleasesAndCannotStartRecovery() = runBlocking {
        withFixture { context, clip ->
            val output = File(context.cacheDir, "codec_cancel_${UUID.randomUUID()}.mp4")
            var attempts = 0
            var allocated = false
            var released = false
            try {
                try {
                    withTimeout(60_000L) {
                        recoverMedia3Export(classifyCodec = { ExportCodecRetryFacts(4002, transient = true) }) {
                            attempts++
                            encode(context, clip, output, 1_600_000_000_000L, softwareVideo = true,
                                injectedFailure = CancellationException("injected cancellation"),
                                onAllocated = { allocated = true }, onReleased = { released = true })
                        }
                    }
                    fail("Cancellation must escape")
                } catch (_: CancellationException) { }
                assertTrue(allocated)
                assertTrue(released)
                assertEquals(1, attempts)
            } finally { output.delete() }
        }
    }

    private suspend fun encode(
        context: Context,
        clip: VideoClip,
        output: File,
        timestampMs: Long,
        softwareVideo: Boolean,
        injectedFailure: Exception?,
        onAllocated: () -> Unit,
        onReleased: () -> Unit,
    ): ExportResult = withContext(Dispatchers.Main.immediate) {
        var transformer: Transformer? = null
        val main = Handler(Looper.getMainLooper())
        withReleasedExportAttempt(release = {
            withContext(Dispatchers.Main.immediate) {
                checkNotNull(transformer).cancel()
                onReleased()
            }
        }) {
            suspendCancellableCoroutine { continuation ->
                val guard = ExactExportFrameGuard()
                val delegate = DefaultEncoderFactory.Builder(context).build()
                val seconds = Mp4TimestampData.unixTimeToMp4TimeSeconds(timestampMs)
                val active = Transformer.Builder(context)
                    .setAssetLoaderFactory(exportAssetLoaderFactory(context, softwareVideo))
                    .setVideoFrameProcessorFactory(GuardedVideoFrameProcessorFactory(guard))
                    .setEncoderFactory(object : Codec.EncoderFactory by delegate {
                        override fun videoNeedsEncoding() = true
                        override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec =
                            delegate.createForVideoEncoding(format, logSessionId).also {
                                main.post {
                                    onAllocated()
                                    if (injectedFailure != null && continuation.isActive) {
                                        continuation.resumeWithException(injectedFailure)
                                    }
                                }
                            }
                    })
                    .setMuxerFactory(InAppMp4Muxer.Factory(InAppMp4Muxer.MetadataProvider { entries ->
                        entries.removeAll { it is Mp4TimestampData }
                        entries.add(Mp4TimestampData(seconds, seconds))
                    }))
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (!continuation.isActive) return
                            try {
                                guard.verify(exportResult.videoFrameCount, fullyTranscoded = true)
                                continuation.resume(exportResult)
                            } catch (failure: Exception) {
                                continuation.resumeWithException(failure)
                            }
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    }).build()
                transformer = active
                active.start(CompositionFactory.editedClip(clip), output.absolutePath)
            }
        }
    }

    private suspend fun withFixture(block: suspend (Context, VideoClip) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("codec_source_", ".mp4", context.cacheDir)
        try {
            instrumentation.context.assets.open("quickcut_exact_boundary.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val uri = Uri.fromFile(source)
            val metadata = MediaReader.metadata(context, uri)
            assertTrue(metadata.hasAudio)
            val clip = VideoClip(1L, uri.toString(), metadata.durationMs, hasAudio = true)
                .withRangeUs(1_950_001L, 3_499_999L)
            block(context, clip)
        } finally { source.delete() }
    }
}
