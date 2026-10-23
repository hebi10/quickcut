package com.hebi.quickcut.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoExportInstrumentedTest {
    private suspend fun withFixture(
        fixtureName: String = "quickcut_fixture.mp4",
        block: suspend (Context, Uri, VideoMetadata) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, fixtureName)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open(fixtureName).use { input ->
                    source.outputStream().use { output -> input.copyTo(output) }
                }
            assertTrue("The emulator fixture is missing", source.length() > 0L)
            val uri = Uri.fromFile(source)
            val metadata = MediaReader.metadata(context, uri)
            assertTrue("Expected a three-second video", metadata.durationMs >= 2_700L)
            block(context, uri, metadata)
        } finally {
            source.delete()
        }
    }

    private suspend fun assertExport(
        context: Context,
        timeline: EditTimeline,
        expectAudio: Boolean,
    ) {
        val started = SystemClock.elapsedRealtime()
        val outputUri = withTimeout(120_000L) {
            VideoExporter(context).export(
                timeline = timeline,
                onProgress = {},
                onFinalizing = {},
            )
        }
        try {
            val elapsedMs = SystemClock.elapsedRealtime() - started
            val exported = MediaReader.metadata(context, outputUri)
            val expectedDurationMs = timeline.totalDurationMs
            Log.i(
                "QuickCutExportTest",
                "clips=" + timeline.clips.size +
                    " targetDurationMs=" + expectedDurationMs +
                    " outputDurationMs=" + exported.durationMs +
                    " elapsedMs=" + elapsedMs,
            )
            assertTrue(
                "Expected duration " + expectedDurationMs +
                    "ms but output was " + exported.durationMs + "ms",
                abs(exported.durationMs - expectedDurationMs) <= 300L,
            )
            if (expectAudio) {
                assertTrue("Expected an audio track", exported.hasAudio)
            } else {
                assertFalse("Expected video without audio", exported.hasAudio)
            }
        } finally {
            context.contentResolver.delete(outputUri, null, null)
        }
    }

    @Test
    fun nativeStreamCopySavesAPlayReadyClipWithoutReencoding() = runBlocking {
        withFixture { context, uri, metadata ->
            val clip = VideoClip(
                id = 1,
                uri = uri.toString(),
                sourceDurationMs = metadata.durationMs,
                startMs = 0L,
                endMs = 2_000L,
                hasAudio = metadata.hasAudio,
            )
            assertNotNull(
                "A standard SDR H264/AAC fixture should use fast stream copy",
                FastMp4Trimmer.prepare(context, clip),
            )
            assertExport(context, EditTimeline(listOf(clip)), true)
        }
    }

    @Test
    fun nativeRemuxWritesDirectlyToGalleryAndPreservesAudio() = runBlocking {
        withFixture { context, uri, metadata ->
            val clip = VideoClip(
                id = 1,
                uri = uri.toString(),
                sourceDurationMs = metadata.durationMs,
                startMs = 0L,
                endMs = 2_000L,
                hasAudio = metadata.hasAudio,
            )
            val plan = FastMp4Trimmer.prepare(context, clip)
                ?: throw AssertionError("Expected a native fast trim for H264/AAC")
            val resultUri = GalleryWriter(context).saveDirect(
                outputBaseName = "test_fast_trim",
                expectedDurationMs = clip.durationMs,
                expectAudio = true,
                writer = { fileDescriptor ->
                    FastMp4Trimmer.write(context, plan, fileDescriptor) {}
                },
                onFinalizing = {},
            )
            try {
                val exported = MediaReader.metadata(context, resultUri)
                assertTrue(abs(exported.durationMs - clip.durationMs) <= 250L)
                assertTrue(exported.hasAudio)
            } finally {
                context.contentResolver.delete(resultUri, null, null)
            }
        }
    }

    @Test
    fun trimmingFourSecondsAtAKeyframeUsesDirectCopy() = runBlocking {
        withFixture("quickcut_keyframes.mp4") { context, uri, metadata ->
            val clip = VideoClip(
                id = 1,
                uri = uri.toString(),
                sourceDurationMs = metadata.durationMs,
                startMs = 4_000L,
                endMs = metadata.durationMs,
                hasAudio = metadata.hasAudio,
            )
            if (Build.VERSION.SDK_INT == 29) {
                val packets = FastBFrameInstrumentedTest()
                val source = File(checkNotNull(uri.path))
                val sourceDigest = packets.digest(source)
                val retainedVideo = packets.samples(source, "video/").filter { it.timeUs >= clip.startUs }
                assertEquals("The four-second tail must contain 120 source pictures", 120, retainedVideo.size)
                assertTrue("The fixture must expose Android 10's nonzero AAC start offset",
                    packets.samples(source, "audio/").first { it.timeUs >= clip.startUs }.timeUs > clip.startUs)
                val inspection = FastMp4Trimmer.inspect(context, clip)
                assertNull("The framework muxer must not shift the first AAC packet", inspection.plan)
                assertEquals(FastMp4Trimmer.UnavailableReason.AUDIO_TIMESTAMPS, inspection.reason)
                val timeline = EditTimeline(listOf(clip))
                val assessment = ExportPlanner.analyze(context, timeline, SaveDateMode.CURRENT)
                assertTrue("Native video copy must remain available for the requested range", assessment.fastAvailable)
                assertFalse("Do not advertise the unsafe framework muxer", assessment.directCopy)
                assertNull("The safe native route must not adjust either boundary", assessment.suggestion)
                val copiedOutput = File.createTempFile("keyframe_audio_reencode_", ".mp4", context.cacheDir)
                var saved: Uri? = null
                var report: ExportReport? = null
                try {
                    val output = withTimeout(120_000L) {
                        VideoExporter(context).export(
                            timeline, {}, {},
                            exportMode = ExportMode.FAST_ONLY,
                            onReport = { report = it },
                        )
                    }
                    saved = output
                    assertEquals(ExportMethod.AUDIO_REENCODE, report?.method)
                    assertEquals(FastExportReason.AUDIO_TIMESTAMPS, report?.diagnostics?.copyReason)
                    checkNotNull(context.contentResolver.openInputStream(output)).use { input ->
                        copiedOutput.outputStream().use { input.copyTo(it) }
                    }
                    MediaReader.validateExport(copiedOutput, clip.durationMs, true)
                    val outputVideo = packets.samples(copiedOutput, "video/")
                    packets.assertCopiedSamples(retainedVideo, outputVideo, clip.startUs)
                    assertEquals(120, outputVideo.size)
                    val decoded = NativeSmartCutInstrumentedTest()
                    decoded.assertDecodedPictures(context, output, 120)
                    decoded.assertAudibleContinuousAudio(context, output,
                        clip.durationMs, mapOf(1 to 880, 8 to 880, 14 to 880))
                    assertEquals("Export must leave the source unchanged", sourceDigest, packets.digest(source))
                } finally {
                    saved?.let { context.contentResolver.delete(it, null, null) }
                    copiedOutput.delete()
                }
                return@withFixture
            }
            val plan = FastMp4Trimmer.prepare(context, clip)
                ?: throw AssertionError("Four-second keyframe trim should be eligible")
            val output = GalleryWriter(context).saveDirect(
                outputBaseName = "test_trim_head_4s",
                expectedDurationMs = clip.durationMs,
                expectAudio = true,
                writer = { fd -> FastMp4Trimmer.write(context, plan, fd) {} },
                onFinalizing = {},
            )
            try {
                val exported = MediaReader.metadata(context, output)
                assertTrue(abs(exported.durationMs - clip.durationMs) <= 250L)
                assertTrue(exported.hasAudio)
            } finally {
                context.contentResolver.delete(output, null, null)
            }
        }
    }

    @Test
    fun preflightDoesNotAllowSilentReencodeForNonSyncTrim() = runBlocking {
        withFixture { context, uri, metadata ->
            val clip = VideoClip(
                id = 1,
                uri = uri.toString(),
                sourceDurationMs = metadata.durationMs,
                startMs = 500L,
                endMs = 2_500L,
                hasAudio = metadata.hasAudio,
            )
            val timeline = EditTimeline(listOf(clip))
            val preflight = ExportPlanner.analyze(context, timeline, SaveDateMode.CURRENT)
            assertFalse("A non-sync 500ms start must not use fast remux",
                preflight.fastAvailable)
            val failure = runCatching {
                VideoExporter(context).export(
                    timeline = timeline,
                    onProgress = {},
                    onFinalizing = {},
                    exportMode = ExportMode.FAST_ONLY,
                )
            }.exceptionOrNull()
            assertTrue(
                "Fast-only export must not start Media3 full encoding",
                failure is ReencodeRequiredException,
            )
        }
    }

    @Test
    fun trimmedVideoIsSavedAndPlayable() = runBlocking {
        withFixture { context, uri, metadata ->
            val clip = VideoClip(
                id = 1,
                uri = uri.toString(),
                sourceDurationMs = metadata.durationMs,
                startMs = 500L,
                endMs = 2_500L,
                hasAudio = metadata.hasAudio,
            )
            assertExport(context, EditTimeline(listOf(clip)), true)
        }
    }

    @Test
    fun separateSegmentsCanBeJoinedWithoutDeletedMiddle() = runBlocking {
        withFixture { context, uri, metadata ->
            val first = VideoClip(
                id = 1,
                uri = uri.toString(),
                sourceDurationMs = metadata.durationMs,
                startMs = 0L,
                endMs = 900L,
                hasAudio = metadata.hasAudio,
            )
            val last = first.copy(id = 2, startMs = 1_900L, endMs = 2_900L)
            assertExport(context, EditTimeline(listOf(first, last)), true)
        }
    }

    @Test
    fun oneMutedClipAndOneAudibleClipProduceAudio() = runBlocking {
        withFixture { context, uri, metadata ->
            val silent = VideoClip(
                id = 1,
                uri = uri.toString(),
                sourceDurationMs = metadata.durationMs,
                startMs = 0L,
                endMs = 1_200L,
                muted = true,
                hasAudio = metadata.hasAudio,
            )
            val audible = silent.copy(
                id = 2,
                startMs = 1_200L,
                endMs = 2_800L,
                muted = false,
            )
            assertExport(context, EditTimeline(listOf(silent, audible)), true)
        }
    }

    @Test
    fun fullyMutedVideoIsSavedWithoutAudio() = runBlocking {
        withFixture { context, uri, metadata ->
            val silent = VideoClip(
                id = 1,
                uri = uri.toString(),
                sourceDurationMs = metadata.durationMs,
                startMs = 0L,
                endMs = 2_000L,
                muted = true,
                hasAudio = metadata.hasAudio,
            )
            assertExport(context, EditTimeline(listOf(silent)), false)
        }
    }
}
