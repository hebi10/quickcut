package com.hebi.quickcut.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FastTailInstrumentedTest {
    @Test fun lateBFrameAfterTheFirstExcludedPacketMustDeclineFrameworkCopy(): Unit = runBlocking {
        withTailFixture { context, input, clip ->
            val packets = FastBFrameInstrumentedTest().samples(input, "video/")
            val requested = clip.copy(endMs = 3_050L, muted = true)
            val crossing = packets.indexOfFirst { it.timeUs >= requested.endUs }
            assertTrue("Fixture must expose the omitted retained packet after the crossing",
                packets.drop(crossing + 1).any { it.timeUs < requested.endUs })
            val inspection = FastMp4Trimmer.inspect(context, requested)
            assertNull("A late B-frame must not produce a truncated successful export", inspection.plan)
            assertEquals(FastMp4Trimmer.UnavailableReason.REORDERED_END, inspection.reason)

            // A stale or manually constructed plan must receive the same runtime
            // protection, independently of the eligibility inspection.
            val full = checkNotNull(FastMp4Trimmer.prepare(context, clip.copy(muted = true)))
            val output = File.createTempFile("late_bframe_output", ".mp4", context.cacheDir)
            try {
                val failure = runCatching {
                    FileOutputStream(output).use {
                        FastMp4Trimmer.write(context,
                            full.copy(endUs = requested.endUs, copyToEndOfSource = false), it.fd, {})
                    }
                }.exceptionOrNull()
                assertTrue("Writer must decline instead of dropping the late retained frame",
                    failure is FastTrimUnavailableException)
            } finally { output.delete() }
        }
    }

    @Test fun fullSourceEndStillCopiesTheLateBFrames(): Unit = runBlocking {
        withTailFixture { context, input, clip ->
            val plan = checkNotNull(FastMp4Trimmer.prepare(context, clip.copy(muted = true)))
            val output = File.createTempFile("full_bframe_output", ".mp4", context.cacheDir)
            try {
                FileOutputStream(output).use { FastMp4Trimmer.write(context, plan, it.fd, {}) }
                val checker = FastBFrameInstrumentedTest()
                checker.assertCopiedSamples(checker.samples(input, "video/"),
                    checker.samples(output, "video/"), 0L)
            } finally { output.delete() }
        }
    }

    @Test fun monotonicPrefixEndingBetweenSyncSamplesStillCopiesEveryRetainedPacket(): Unit = runBlocking {
        withTailFixture { context, input, clip ->
            val requested = clip.copy(endMs = 2_050L, muted = true)
            val plan = checkNotNull(FastMp4Trimmer.prepare(context, requested))
            val output = File.createTempFile("monotonic_prefix_output", ".mp4", context.cacheDir)
            try {
                FileOutputStream(output).use { FastMp4Trimmer.write(context, plan, it.fd, {}) }
                val checker = FastBFrameInstrumentedTest()
                checker.assertCopiedSamples(checker.samples(input, "video/").filter { it.timeUs < requested.endUs },
                    checker.samples(output, "video/"), 0L)
            } finally { output.delete() }
        }
    }

    @Test fun shortAudioDoesNotMakeRemuxInspectReorderedVideoBeyondTrimEnd(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File.createTempFile("unequal_tail", ".mp4", context.cacheDir)
        val output = File.createTempFile("unequal_output", ".mp4", context.cacheDir)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_unequal_tail.mp4").use { source ->
                input.outputStream().use { source.copyTo(it) }
            }
            // Establish that the regression fixture has a short audio stream and
            // reordered video only in the discarded tail, without timing thresholds.
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(input.absolutePath)
                var audioEnd = -1L
                var reversalAt = -1L
                for (track in 0 until extractor.trackCount) {
                    extractor.selectTrack(track)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val audio = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
                    var previous = -1L
                    do {
                        val time = extractor.sampleTime
                        if (time < 0) break
                        if (audio) audioEnd = maxOf(audioEnd, time)
                        else if (time < previous && reversalAt < 0) reversalAt = time
                        previous = time
                    } while (extractor.advance())
                    extractor.unselectTrack(track)
                }
                assertTrue("Audio must end before requested trim end", audioEnd in 1 until 2_000_000L)
                assertTrue("Reordered video must occur only beyond trim end", reversalAt >= 2_000_000L)
            } finally { extractor.release() }
            val metadata = MediaReader.metadata(context, Uri.fromFile(input))
            val clip = VideoClip(id = 1L, uri = Uri.fromFile(input).toString(),
                sourceDurationMs = metadata.durationMs, endMs = 2_000L, hasAudio = true)
            val plan = FastMp4Trimmer.prepare(context, clip)
            assertNotNull("Retained prefix is suitable for remux", plan)
            FileOutputStream(output).use { FastMp4Trimmer.write(context, plan!!, it.fd, {}) }
            MediaReader.validateExport(output, 2_000, true)
            val checker = FastBFrameInstrumentedTest()
            checker.assertCopiedSamples(checker.samples(input, "video/").filter { it.timeUs < 2_000_000L },
                checker.samples(output, "video/"), 0L)
            Unit
        } finally { input.delete(); output.delete() }
    }

    private suspend fun withTailFixture(
        block: suspend (android.content.Context, File, VideoClip) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File.createTempFile("unequal_tail", ".mp4", context.cacheDir)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_unequal_tail.mp4").use { source ->
                input.outputStream().use { source.copyTo(it) }
            }
            val uri = Uri.fromFile(input)
            val metadata = MediaReader.metadata(context, uri)
            block(context, input, VideoClip(id = 1L, uri = uri.toString(),
                sourceDurationMs = metadata.durationMs, hasAudio = metadata.hasAudio))
        } finally { input.delete() }
    }
}
