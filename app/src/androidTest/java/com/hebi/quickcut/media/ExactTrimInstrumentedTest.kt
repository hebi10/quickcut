package com.hebi.quickcut.media

import android.content.Context
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class ExactTrimInstrumentedTest {
    @Test
    fun fiftyMillisecondsBeforeKeyframeKeepsTheRequestedRedFramesAndAudio() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("exact_boundary_source_", ".mp4", context.cacheDir)
        var output: Uri? = null
        try {
            instrumentation.context.assets.open("quickcut_exact_boundary.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val sourceUri = Uri.fromFile(source)
            val metadata = MediaReader.metadata(context, sourceUri)
            assertTrue("Fixture must include its AAC audio track", metadata.hasAudio)
            assertRed(centerColor(context, sourceUri, 1_950_000L), "Fixture before the keyframe")
            val blueAtKeyframe = centerColor(context, sourceUri, 2_000_000L)
            assertTrue("Fixture must switch to blue exactly at the two-second keyframe",
                Color.blue(blueAtKeyframe) > Color.red(blueAtKeyframe) + 100)

            val clip = VideoClip(
                id = 1L,
                uri = sourceUri.toString(),
                sourceDurationMs = metadata.durationMs,
                hasAudio = metadata.hasAudio,
                startMs = 1_950L,
            )
            val originalClip = clip.copy()
            var report: ExportReport? = null
            val saved = VideoExporter(context).export(
                timeline = EditTimeline(listOf(clip)),
                onProgress = {},
                onFinalizing = {},
                exportMode = ExportMode.ALLOW_REENCODE,
                onOutputCreated = { output = it },
                onReport = { report = it },
            )
            output = saved

            assertRed(
                centerColor(context, saved, 0L),
                "The first saved frame must retain red footage before the next keyframe",
            )
            val savedMetadata = MediaReader.validateExport(
                context, saved, expectedDurationMs = clip.durationMs, expectAudio = true,
            )
            assertTrue("Duration must preserve the requested interval within one 30fps frame: " +
                "requested=${clip.durationMs}ms actual=${savedMetadata.durationMs}ms",
                abs(savedMetadata.durationMs - clip.durationMs) <= 34L)
            assertTrue("The original audio track must survive", savedMetadata.hasAudio)
            assertEquals("Export must not move or mutate the original requested clip", originalClip, clip)
            assertEquals("PC-encoded AVC requires a full fallback, which must be reported honestly",
                ExportMethod.VIDEO_REENCODE, report?.method)
        } finally {
            output?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }

    @Test
    fun nativeCopyReportsOnlyAfterCommitAndAReportExceptionCannotExportTwice() = runBlocking {
        withFixture { context, _, clip ->
            var output: Uri? = null
            var created = 0
            var committed = 0
            var reports = 0
            var deliveredOnMain = false
            var wasPublished = false
            var report: ExportReport? = null
            try {
                val saved = VideoExporter(context).export(
                    EditTimeline(listOf(clip.copy(startMs = 2_000L))), {}, {},
                    onOutputCreated = { output = it; created++ },
                    onCommitted = { committed++ },
                    onReport = {
                        reports++
                        report = it
                        deliveredOnMain = Looper.myLooper() == Looper.getMainLooper()
                        wasPublished = committed == 1 && context.contentResolver.query(
                            output!!, arrayOf(MediaStore.Video.Media.IS_PENDING), null, null, null,
                        )?.use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 0 } == true
                        throw FastTrimUnavailableException("A display-only report consumer failed")
                    },
                )
                assertEquals("A display failure must preserve the committed URI", output, saved)
                assertEquals("No second output may be created", 1, created)
                assertEquals(1, committed)
                assertEquals(1, reports)
                assertTrue("Reporting must run on Main", deliveredOnMain)
                assertTrue("The output must be published before reporting", wasPublished)
                assertEquals(ExportMethod.ORIGINAL_COPY, report?.method)
                MediaReader.validateExport(context, saved, clip.sourceDurationMs - 2_000L, true)
            } finally {
                output?.let { context.contentResolver.delete(it, null, null) }
            }
        }
    }

    @Test
    fun aRejectedFastOnlyExportDoesNotProduceASuccessReport() = runBlocking {
        withFixture { context, _, clip ->
            var report: ExportReport? = null
            var output: Uri? = null
            try {
                val failure = runCatching {
                    VideoExporter(context).export(
                        EditTimeline(listOf(clip.copy(startMs = 1_950L))), {}, {},
                        exportMode = ExportMode.FAST_ONLY,
                        onOutputCreated = { output = it },
                        onReport = { report = it },
                    )
                }.exceptionOrNull()
                assertTrue(failure is ReencodeRequiredException)
                assertEquals(null, report)
                assertEquals(null, output)
            } finally {
                output?.let { context.contentResolver.delete(it, null, null) }
            }
        }
    }

    @Test
    fun sameDeviceEncodedSourceUsesPartialEncodingWithoutMovingTheRequestedStart() = runBlocking {
        // Matching the device encoder's CSD is deliberately part of this positive
        // fixture. It does not assert optimization for arbitrary imported files.
        assumeTrue("The known encoder-matching fixture runs on SDK 33+ emulators",
            Build.VERSION.SDK_INT >= 33 && Util.isRunningOnEmulator())
        withFixture { context, _, clip ->
            val encoded = File(context.cacheDir, "exact_matching_encoder_${UUID.randomUUID()}.mp4")
            var output: Uri? = null
            try {
                withTimeout(120_000L) {
                    encodeWithDefaultDeviceEncoder(context, clip, encoded)
                    val encodedUri = Uri.fromFile(encoded)
                    val metadata = MediaReader.validateExport(encoded, clip.durationMs, true)
                    val requested = clip.copy(
                        uri = encodedUri.toString(),
                        sourceDurationMs = metadata.durationMs,
                        startMs = 500L,
                        endMs = 4_500L,
                    )
                    assertTrue("The same-device fixture must safely permit partial encoding",
                        ExactTrimOptimization.canOptimize(context, requested))
                    var report: ExportReport? = null
                    val saved = VideoExporter(context).export(
                        EditTimeline(listOf(requested)), {}, {},
                        onOutputCreated = { output = it },
                        onReport = { report = it },
                    )
                    output = saved
                    assertEquals("The optimized path must actually run, not silently fully reencode",
                        ExportResult.OPTIMIZATION_SUCCEEDED, report?.optimizationResult)
                    assertEquals(ExportMethod.PARTIAL_REENCODE, report?.method)
                    assertRed(centerColor(context, saved, 0L), "Partial encoding must retain the chosen red start")
                    val result = MediaReader.validateExport(context, saved, requested.durationMs, true)
                    assertTrue(abs(result.durationMs - requested.durationMs) <= 34L)
                    assertEquals(500L, requested.startMs)
                    assertEquals(4_500L, requested.endMs)
                }
            } finally {
                output?.let { context.contentResolver.delete(it, null, null) }
                encoded.delete()
            }
        }
    }

    @Test
    fun lowFrameGapAndMutedSourceUseTheSameConservativeMetadataGuard() = runBlocking {
        withFixture { context, _, clip ->
            val near = clip.copy(startMs = 1_980L)
            assertFalse("No video frame precedes the next sync, so Media3 would move the start",
                ExactTrimOptimization.canOptimize(context, near))
            assertFalse("Muting must not bypass source metadata restrictions",
                ExactTrimOptimization.canOptimize(context, near.copy(muted = true)))
            assertTrue(ExactTrimOptimization.canOptimize(context, clip.copy(startMs = 1_950L)))
        }
    }

    private suspend fun encodeWithDefaultDeviceEncoder(context: Context, clip: VideoClip, output: File) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val delegate = DefaultEncoderFactory.Builder(context).build()
                val transformer = Transformer.Builder(context)
                    .setEncoderFactory(object : Codec.EncoderFactory by delegate {
                        override fun videoNeedsEncoding() = true
                    })
                    .setMuxerFactory(InAppMp4Muxer.Factory())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    })
                    .build()
                continuation.invokeOnCancellation {
                    val stop = Runnable { transformer.cancel() }
                    if (Looper.myLooper() == Looper.getMainLooper()) stop.run()
                    else Handler(Looper.getMainLooper()).post(stop)
                }
                try {
                    transformer.start(CompositionFactory.editedClip(clip), output.absolutePath)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }

    private suspend fun withFixture(block: suspend (Context, File, VideoClip) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("exact_boundary_source_", ".mp4", context.cacheDir)
        try {
            instrumentation.context.assets.open("quickcut_exact_boundary.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val sourceUri = Uri.fromFile(source)
            val metadata = MediaReader.metadata(context, sourceUri)
            block(context, source, VideoClip(
                id = 1L,
                uri = sourceUri.toString(),
                sourceDurationMs = metadata.durationMs,
                hasAudio = metadata.hasAudio,
            ))
        } finally {
            source.delete()
        }
    }

    private fun centerColor(context: Context, uri: Uri, timeUs: Long): Int {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                160,
                90,
            ) ?: throw AssertionError("The requested frame at ${timeUs}us must decode")
            return try {
                frame.getPixel(frame.width / 2, frame.height / 2)
            } finally {
                frame.recycle()
            }
        } finally {
            retriever.release()
        }
    }

    private fun assertRed(color: Int, message: String) {
        assertTrue("$message: red=${Color.red(color)} blue=${Color.blue(color)}",
            Color.red(color) > 150 && Color.red(color) > Color.blue(color) + 100)
    }
}
