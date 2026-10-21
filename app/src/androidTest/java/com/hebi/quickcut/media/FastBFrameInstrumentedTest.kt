package com.hebi.quickcut.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FastBFrameInstrumentedTest {
    @Test
    fun headTrimToSourceEndCopiesEveryBFrameAndAudioSampleWithoutPreroll() = runBlocking {
        withFixture("quickcut_bframes.mp4") { context, source, clip ->
            val retained = clip.copy(startMs = 4_000L)
            val sourceVideo = samples(source, "video/").filter { it.timeUs >= 4_000_000L }
            assertTrue("Fixture must contain reordered presentation timestamps",
                sourceVideo.zipWithNext().any { (first, second) -> second.timeUs < first.timeUs })
            val plan = FastMp4Trimmer.prepare(context, retained)
                ?: throw AssertionError("B-frames with the complete tail should support fast copy")
            val output = File.createTempFile("fast_bframe_output_", ".mp4", context.cacheDir)
            try {
                FileOutputStream(output).use { FastMp4Trimmer.write(context, plan, it.fd) {} }
                MediaReader.validateExport(output, retained.durationMs, true)
                assertCopiedSamples(sourceVideo, samples(output, "video/"), plan.startUs)
                assertCopiedSamples(
                    samples(source, "audio/").filter { it.timeUs >= plan.startUs },
                    samples(output, "audio/"),
                    plan.startUs,
                )
            } finally {
                output.delete()
            }
        }
    }

    @Test
    fun nonSyncThreeSecondBFrameTrimSuggestsTheNextSafeFourSecondStart() = runBlocking {
        withFixture("quickcut_bframes.mp4") { context, _, clip ->
            val assessment = ExportPlanner.analyze(
                context, EditTimeline(listOf(clip.copy(startMs = 3_000L))), SaveDateMode.CURRENT,
            )
            assertFalse("The requested non-keyframe start must not silently change", assessment.fastAvailable)
            assertEquals("A full-tail B-frame source should offer a safe next keyframe",
                4_000L, assessment.suggestedStartMs)
        }
    }

    @Test
    fun nonSyncStartExplainsTheKeyframeConditionInsteadOfBlamingTheCodec() = runBlocking {
        withFixture("quickcut_keyframes.mp4") { context, _, clip ->
            val assessment = ExportPlanner.analyze(
                context, EditTimeline(listOf(clip.copy(startMs = 3_000L))), SaveDateMode.CURRENT,
            )
            assertFalse(assessment.fastAvailable)
            assertTrue("Explain the actual keyframe boundary condition: ${assessment.explanation}",
                assessment.explanation.contains("키프레임"))
            assertEquals(4_000L, assessment.suggestedStartMs)
        }
    }

    @Test
    fun bFrameTrimmedTailOffersAnEarlierSafeEndWithoutChangingTheRequest() = runBlocking {
        withFixture("quickcut_bframes.mp4") { context, _, clip ->
            val retained = clip.copy(startMs = 4_000L, endMs = 7_000L)
            assertNull("A B-frame reference beyond the trim end must not be discarded",
                FastMp4Trimmer.prepare(context, retained))
            val assessment = ExportPlanner.analyze(
                context, EditTimeline(listOf(retained)), SaveDateMode.CURRENT,
            )
            assertFalse(assessment.fastAvailable)
            assertNull(assessment.suggestedStartMs)
            val suggestion = checkNotNull(assessment.suggestion)
            assertEquals(6_000L, suggestion.adjusted.clips.single().endMs)
            assertEquals(1_000L, suggestion.extraRemovedMs)
            assertEquals(7_000L, retained.endMs)
            assertTrue("Explain why trimming the tail prevents safe B-frame copy: ${assessment.explanation}",
                assessment.explanation.contains("끝"))
        }
    }

    @Test
    fun openGopFramesBeforeTheChosenSyncRemainIneligibleForFastOnly() = runBlocking {
        withFixture("quickcut_open_gop.mp4") { context, source, clip ->
            val selected = samples(source, "video/", 4_000_000L)
            assertEquals("The seek must begin at the selected keyframe", 4_000_000L, selected.first().timeUs)
            assertTrue("The Android extractor must expose leading frames after this sync sample",
                selected.drop(1).take(12).any { it.timeUs < 4_000_000L })
            val retained = clip.copy(startMs = 4_000L)
            assertNull("Do not assume leading open-GOP frames can safely be discarded",
                FastMp4Trimmer.prepare(context, retained))
            var output: Uri? = null
            var outputCreated = false
            try {
                val failure = runCatching {
                    output = VideoExporter(context).export(
                        timeline = EditTimeline(listOf(retained)),
                        onProgress = {},
                        onFinalizing = {},
                        exportMode = ExportMode.FAST_ONLY,
                        onOutputCreated = { outputCreated = true },
                    )
                }.exceptionOrNull()
                assertTrue("Fast-only must fail without silently reencoding", failure is ReencodeRequiredException)
                assertFalse("Ineligible fast-only exports must not create an output", outputCreated)
            } finally {
                output?.let { context.contentResolver.delete(it, null, null) }
            }
        }
    }

    private data class Sample(val timeUs: Long, val flags: Int, val digest: String)

    private fun samples(file: File, mimePrefix: String, seekUs: Long? = null): List<Sample> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true
            }
            extractor.selectTrack(track)
            if (seekUs != null) extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
            buildList {
                // AAC priming may have a negative PTS; track exhaustion identifies EOS.
                while (extractor.sampleTrackIndex >= 0) {
                    val buffer = ByteBuffer.allocate(extractor.sampleSize.toInt())
                    val size = extractor.readSampleData(buffer, 0)
                    assertEquals(buffer.capacity(), size)
                    val digest = MessageDigest.getInstance("SHA-256").digest(buffer.array())
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    add(Sample(extractor.sampleTime, extractor.sampleFlags, digest))
                    if (!extractor.advance()) break
                }
            }
        } finally {
            extractor.release()
        }
    }

    private fun assertCopiedSamples(source: List<Sample>, output: List<Sample>, startUs: Long) {
        assertTrue("Fixture must have retained samples", source.isNotEmpty())
        assertEquals("Every retained packet, including the final B-frame, must survive", source.size, output.size)
        source.zip(output).forEachIndexed { index, (before, after) ->
            assertEquals("Compressed packet $index must be copied without reencoding", before.digest, after.digest)
            assertEquals("Packet $index sync flag must be preserved", before.flags, after.flags)
            assertTrue("Packet $index presentation timestamp must retain its source offset",
                abs((before.timeUs - startUs) - after.timeUs) <= 1_000L)
            assertTrue("The removed head must not survive as hidden preroll", after.timeUs >= 0L)
        }
    }

    private suspend fun withFixture(
        fixtureName: String,
        block: suspend (Context, File, VideoClip) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("fast_bframe_source_", ".mp4", context.cacheDir)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open(fixtureName).use { source ->
                file.outputStream().use { destination -> source.copyTo(destination) }
            }
            val uri = Uri.fromFile(file)
            val metadata = MediaReader.metadata(context, uri)
            val clip = VideoClip(
                id = 1L,
                uri = uri.toString(),
                sourceDurationMs = metadata.durationMs,
                hasAudio = metadata.hasAudio,
            )
            block(context, file, clip)
        } finally {
            file.delete()
        }
    }
}
