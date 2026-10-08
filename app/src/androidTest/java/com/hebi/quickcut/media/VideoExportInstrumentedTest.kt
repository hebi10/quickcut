package com.hebi.quickcut.media

import android.content.Context
import android.net.Uri
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoExportInstrumentedTest {
    private suspend fun withFixture(
        block: suspend (Context, Uri, VideoMetadata) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "quickcut_fixture.mp4")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("quickcut_fixture.mp4").use { input ->
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
