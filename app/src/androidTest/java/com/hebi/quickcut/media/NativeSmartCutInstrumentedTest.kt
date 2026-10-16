package com.hebi.quickcut.media

import android.content.Context
import android.graphics.Color
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real imported AVC/AAC files: exact boundaries plus independently verified middle copy. */
@RunWith(AndroidJUnit4::class)
class NativeSmartCutInstrumentedTest {
    @Test
    fun externalAvcKeepsRedBeforeTheKeyframeAndCopiesTheCompleteMiddleGop() = runBlocking {
        withSource("quickcut_exact_boundary.mp4") { context, source, original ->
            val requested = original.copy(startMs = 1_950L, endMs = 5_500L)
            withExport(context, EditTimeline(listOf(requested))) { saved, report ->
                assertColor(context, saved, 0L, red = true)
                assertColor(context, saved, 100_000L, red = false)
                assertColor(context, saved, 3_500_000L, red = false)
                assertDurationAndAudio(context, saved, 3_550L)
                assertEquals("Imported AVC must encode the boundaries and copy the middle",
                    ExportMethod.PARTIAL_REENCODE, report.method)
                // 1.95s lies between 30fps frames. Frame selection may quantize by
                // one frame, but it must not jump to the 2s keyframe and lose red.
                assertMiddleCopied(context, source, saved, 2_000_000L, 4_000_000L,
                    requestedStartUs = 1_950_000L, timestampToleranceUs = 34_000L)
                assertEquals(1_950L, requested.startMs)
                assertEquals(5_500L, requested.endMs)
            }
        }
    }

    @Test
    fun threeSecondStartAndTrimmedTailKeepTheirFramesWhileTheMiddleIsCopied() = runBlocking {
        withSource("quickcut_keyframes.mp4") { context, source, original ->
            val requested = original.copy(startMs = 3_000L, endMs = 7_500L)
            val chosen = frame(context, source, 3_000_000L)
            val shifted = frame(context, source, 4_000_000L)
            assertTrue("Fixture must distinguish the requested 3s start from the next 4s keyframe",
                meanDifference(chosen, shifted) > 12.0)
            withExport(context, EditTimeline(listOf(requested))) { saved, report ->
                val first = frame(context, saved, 0L)
                assertTrue("The first output frame must match 3s, not silently move to 4s",
                    meanDifference(first, chosen) < 10.0 &&
                        meanDifference(first, chosen) + 5.0 < meanDifference(first, shifted))
                // Decode on both sides of the copied GOP and at the tail. Matching
                // packet bytes alone cannot prove that codec transitions play.
                listOf(1_000_000L, 2_966_667L, 3_000_000L, 4_466_667L).forEach { timeUs ->
                    assertTrue("The output frame at ${timeUs}us must decode the same source moment",
                        meanDifference(frame(context, source, timeUs + 3_000_000L),
                            frame(context, saved, timeUs)) < 10.0)
                }
                assertDurationAndAudio(context, saved, 4_500L)
                assertEquals(ExportMethod.PARTIAL_REENCODE, report.method)
                assertMiddleCopied(context, source, saved, 4_000_000L, 6_000_000L,
                    requestedStartUs = 3_000_000L, timestampToleranceUs = 1_000L)
                assertEquals(3_000L, requested.startMs)
                assertEquals(7_500L, requested.endMs)
            }
        }
    }

    @Test
    fun startsImmediatelyBeforeAndAfterAKeyframeKeepTheRequestedTimeline() = runBlocking {
        withSource("quickcut_exact_boundary.mp4") { context, source, original ->
            for (startMs in listOf(1_980L, 2_010L)) {
                val requested = original.copy(startMs = startMs, endMs = 5_500L)
                withExport(context, EditTimeline(listOf(requested))) { saved, report ->
                    // There is no frame between 1.98s and 2s in this 30fps fixture;
                    // both intervals correctly begin with an available blue frame.
                    assertColor(context, saved, 0L, red = false)
                    assertDurationAndAudio(context, saved, 5_500L - startMs)
                    assertFalse("A non-keyframe request must not be reported as pure original copy",
                        report.method == ExportMethod.ORIGINAL_COPY)
                    if (report.method == ExportMethod.PARTIAL_REENCODE) {
                        assertHasCopiedRun(context, source, saved, startMs * 1_000L, 5_500_000L)
                    }
                    assertEquals(startMs, requested.startMs)
                    assertEquals(5_500L, requested.endMs)
                }
            }
        }
    }

    @Test
    fun unsupportedMultiClipTimelineFallsBackOnceWithoutMovingEitherBoundary() = runBlocking {
        withSource("quickcut_exact_boundary.mp4") { context, _, original ->
            val timeline = EditTimeline(listOf(
                original.copy(startMs = 500L, endMs = 1_500L),
                original.copy(id = 2L, startMs = 2_500L, endMs = 3_500L),
            ))
            val unchanged = timeline.copy(clips = timeline.clips.map { it.copy() })
            withExport(context, timeline) { saved, report ->
                assertColor(context, saved, 0L, red = true)
                assertColor(context, saved, 900_000L, red = true)
                assertColor(context, saved, 1_100_000L, red = false)
                assertDurationAndAudio(context, saved, 2_000L)
                assertEquals("Unsupported compositions must retain the normal full fallback",
                    ExportMethod.VIDEO_REENCODE, report.method)
                assertEquals(unchanged, timeline)
            }
        }
    }

    private suspend fun withSource(
        asset: String,
        block: suspend (Context, Uri, VideoClip) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("smartcut_source_", ".mp4", context.cacheDir)
        try {
            instrumentation.context.assets.open(asset).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val uri = Uri.fromFile(file)
            val metadata = MediaReader.metadata(context, uri)
            assertTrue("The source fixture must contain AAC", metadata.hasAudio)
            block(context, uri, VideoClip(1L, uri.toString(), metadata.durationMs,
                hasAudio = metadata.hasAudio))
        } finally {
            file.delete()
        }
    }

    private suspend fun withExport(
        context: Context,
        timeline: EditTimeline,
        block: suspend (Uri, ExportReport) -> Unit,
    ) {
        val outputs = mutableListOf<Uri>()
        var committed: Uri? = null
        var commitCount = 0
        var reportCount = 0
        var report: ExportReport? = null
        try {
            val saved = VideoExporter(context).export(
                timeline, {}, {}, exportMode = ExportMode.ALLOW_REENCODE,
                onOutputCreated = { outputs.add(it) },
                onCommitted = { committed = it; commitCount++ },
                onReport = {
                    assertTrue("Report must follow publication", committed != null)
                    report = it
                    reportCount++
                },
            )
            assertEquals("Internal fallback must create only one gallery output", listOf(saved), outputs)
            assertEquals(saved, committed)
            assertEquals(1, commitCount)
            assertEquals(1, reportCount)
            val pending = context.contentResolver.query(saved,
                arrayOf(MediaStore.Video.Media.IS_PENDING), null, null, null)?.use {
                if (it.moveToFirst()) it.getInt(0) else -1
            }
            assertEquals("The saved video must be published", 0, pending)
            block(saved, report ?: throw AssertionError("A committed export requires a processing report"))
        } finally {
            outputs.distinct().forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun assertDurationAndAudio(context: Context, uri: Uri, expectedMs: Long) {
        val metadata = MediaReader.validateExport(context, uri, expectedMs, expectAudio = true)
        assertTrue("Requested=${expectedMs}ms, actual=${metadata.durationMs}ms",
            abs(metadata.durationMs - expectedMs) <= 34L)
        assertAudibleContinuousAudio(context, uri, expectedMs)
    }

    private data class VideoSample(val timeUs: Long, val vcl: List<String>)

    private fun videoSamples(context: Context, uri: Uri): List<VideoSample> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc"
            }
            extractor.selectTrack(track)
            return buildList {
                while (extractor.sampleTrackIndex >= 0) {
                    val buffer = ByteBuffer.allocate(extractor.sampleSize.toInt())
                    assertEquals(buffer.capacity(), extractor.readSampleData(buffer, 0))
                    val hashes = nalUnits(buffer.array()).filter { nal ->
                        (nal[0].toInt() and 0x1f) in 1..5
                    }.map { nal ->
                        MessageDigest.getInstance("SHA-256").digest(nal)
                            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    }
                    assertTrue("Each extracted AVC access unit must contain VCL data", hashes.isNotEmpty())
                    add(VideoSample(extractor.sampleTime, hashes))
                    if (!extractor.advance()) break
                }
            }
        } finally {
            extractor.release()
        }
    }

    /** Ignore container framing and parameter sets, never the encoded picture payload. */
    private fun nalUnits(bytes: ByteArray): List<ByteArray> {
        fun startCodeLength(at: Int): Int = when {
            at + 3 < bytes.size && bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() &&
                bytes[at + 2] == 0.toByte() && bytes[at + 3] == 1.toByte() -> 4
            at + 2 < bytes.size && bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() &&
                bytes[at + 2] == 1.toByte() -> 3
            else -> 0
        }
        if (startCodeLength(0) > 0) {
            val result = mutableListOf<ByteArray>()
            var start = startCodeLength(0)
            var cursor = start
            while (cursor < bytes.size) {
                val code = startCodeLength(cursor)
                if (code > 0) {
                    assertTrue(cursor > start)
                    result.add(bytes.copyOfRange(start, cursor))
                    start = cursor + code
                    cursor = start
                } else cursor++
            }
            assertTrue(start < bytes.size)
            result.add(bytes.copyOfRange(start, bytes.size))
            return result
        }
        // Android normally exposes Annex B. Accept legal AVCC length sizes too.
        for (lengthSize in listOf(4, 2, 1)) {
            val result = mutableListOf<ByteArray>()
            var cursor = 0
            var valid = true
            while (cursor + lengthSize <= bytes.size) {
                var length = 0L
                repeat(lengthSize) { length = (length shl 8) or (bytes[cursor++].toLong() and 0xff) }
                if (length <= 0L || length > bytes.size - cursor) {
                    valid = false
                    break
                }
                result.add(bytes.copyOfRange(cursor, cursor + length.toInt()))
                cursor += length.toInt()
            }
            if (valid && cursor == bytes.size && result.isNotEmpty()) return result
        }
        throw AssertionError("Extracted AVC packet has neither Annex B nor AVCC framing")
    }

    private fun assertMiddleCopied(
        context: Context,
        source: Uri,
        output: Uri,
        middleStartUs: Long,
        middleEndUs: Long,
        requestedStartUs: Long,
        timestampToleranceUs: Long,
    ) {
        val before = videoSamples(context, source).filter { it.timeUs in middleStartUs until middleEndUs }
        val after = videoSamples(context, output)
        assertEquals("The fixture's complete two-second middle GOP has 60 frames", 60, before.size)
        val candidates = after.indices.filter { index ->
            abs(after[index].timeUs - (middleStartUs - requestedStartUs)) <= timestampToleranceUs &&
                after[index].vcl == before.first().vcl
        }
        val first = candidates.firstOrNull { offset ->
            offset + before.size <= after.size && before.indices.all { i ->
                before[i].vcl == after[offset + i].vcl &&
                    abs(before[i].timeUs - requestedStartUs - after[offset + i].timeUs) <= timestampToleranceUs
            }
        }
        assertTrue("Every middle GOP picture must retain its compressed VCL bytes, order, and source PTS",
            first != null)
    }

    private fun assertHasCopiedRun(context: Context, source: Uri, output: Uri, startUs: Long, endUs: Long) {
        val before = videoSamples(context, source).filter { it.timeUs in startUs until endUs }
        val after = videoSamples(context, output)
        val found = before.indices.any { sourceIndex ->
            sourceIndex + 15 <= before.size && after.indices.any { outputIndex ->
                outputIndex + 15 <= after.size && (0 until 15).all { offset ->
                    val a = before[sourceIndex + offset]
                    val b = after[outputIndex + offset]
                    a.vcl == b.vcl && abs(a.timeUs - startUs - b.timeUs) <= 34_000L
                }
            }
        }
        assertTrue("PARTIAL_REENCODE requires an actual copied run, not merely a successful full encode", found)
    }

    private fun frame(context: Context, uri: Uri, timeUs: Long): IntArray {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val bitmap = retriever.getScaledFrameAtTime(timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST, 160, 120)
                ?: throw AssertionError("Frame at ${timeUs}us must decode")
            return try {
                assertEquals(160, bitmap.width)
                assertEquals(120, bitmap.height)
                IntArray(160 * 120).also { bitmap.getPixels(it, 0, 160, 0, 0, 160, 120) }
            } finally {
                bitmap.recycle()
            }
        } finally {
            retriever.release()
        }
    }

    private fun meanDifference(a: IntArray, b: IntArray): Double = a.indices.sumOf { i ->
        (abs(Color.red(a[i]) - Color.red(b[i])) + abs(Color.green(a[i]) - Color.green(b[i])) +
            abs(Color.blue(a[i]) - Color.blue(b[i]))).toLong()
    }.toDouble() / (a.size * 3)

    private fun assertColor(context: Context, uri: Uri, timeUs: Long, red: Boolean) {
        val color = frame(context, uri, timeUs)[60 * 160 + 80]
        val wanted = if (red) Color.red(color) else Color.blue(color)
        val unwanted = if (red) Color.blue(color) else Color.red(color)
        assertTrue("Frame at ${timeUs}us must be ${if (red) "red" else "blue"}, actual=$color",
            wanted > 150 && wanted > unwanted + 100)
    }

    private fun assertAudibleContinuousAudio(context: Context, uri: Uri, expectedMs: Long) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var samples = 0L
            var windowSamples = 0L
            var windowAmplitude = 0L
            var windows = 0
            var previousEndUs: Long? = null
            val deadline = SystemClock.elapsedRealtime() + 20_000L
            while (!outputEnded) {
                assertTrue("AAC decoding must terminate", SystemClock.elapsedRealtime() < deadline)
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)!!
                        input.clear()
                        val size = if (extractor.sampleTrackIndex >= 0) extractor.readSampleData(input, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            // Negative AAC priming PTS is legal, not end of stream.
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val actual = codec.outputFormat
                        sampleRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            assertEquals(AudioFormat.ENCODING_PCM_16BIT, actual.getInteger(MediaFormat.KEY_PCM_ENCODING))
                        }
                    }
                    else -> if (index >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            previousEndUs?.let { end ->
                                assertTrue("Decoded audio may not contain a gap or overlap at a splice",
                                    abs(info.presentationTimeUs - end) <= 30_000L)
                            }
                            val buffer = codec.getOutputBuffer(index)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val count = info.size / 2
                            repeat(count) {
                                windowAmplitude += abs(buffer.short.toInt()).toLong()
                                windowSamples++
                                if (windowSamples >= sampleRate.toLong() * channels / 4) {
                                    assertTrue("Each 250ms audio window must retain the audible fixture tone",
                                        windowAmplitude / windowSamples > 100L)
                                    windows++
                                    windowSamples = 0L
                                    windowAmplitude = 0L
                                }
                            }
                            samples += count
                            previousEndUs = info.presentationTimeUs + count.toLong() * 1_000_000L / (sampleRate * channels)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            val decodedMs = samples * 1_000L / (sampleRate * channels)
            assertTrue("Decoded audio must span the requested clip: expected=${expectedMs}ms decoded=${decodedMs}ms",
                abs(decodedMs - expectedMs) <= 80L)
            assertTrue("The complete audio, including both boundaries, must be decoded", windows >= expectedMs / 250L - 1L)
        } finally {
            decoder?.release()
            extractor.release()
        }
    }
}
