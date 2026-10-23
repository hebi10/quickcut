package com.hebi.quickcut.media

import android.content.Context
import android.content.ContentValues
import android.graphics.Color
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real imported AVC/AAC files: exact boundaries plus independently verified middle copy. */
@RunWith(AndroidJUnit4::class)
class NativeSmartCutInstrumentedTest {
    @Test
    fun reorderedAudibleRangesUsePartialProcessingAndKeepAudioAfterTheJoin() = runBlocking {
        withSource("quickcut_bframes.mp4") { context, source, original ->
            val timeline = EditTimeline(listOf(
                original.copy(startMs = 4_500L, endMs = 8_000L),
                original.copy(id = 2L, startMs = 0L, endMs = 3_000L),
            ))
            withExport(context, timeline) { saved, report ->
                assertEquals(ExportMethod.PARTIAL_REENCODE, report.method)
                assertDurationAndAudio(context, saved, 6_500L, mapOf(1 to 880, 7 to 1100, 15 to 440, 24 to 660))
                assertTrue(meanDifference(frame(context, source, 4_500_000L),
                    frame(context, saved, 0L)) < 10.0)
                assertTrue(meanDifference(frame(context, source, 0L),
                    frame(context, saved, 3_500_000L)) < 10.0)
            }
        }
    }

    @Test
    fun originalDateCopyRemainsAvailableInFastOnlyMode() = runBlocking {
        withSource("quickcut_keyframes.mp4") { context, source, original ->
            val date = OriginalTimestamp(1_609_459_200_000L, "fixture")
            withExport(context, EditTimeline(listOf(original.copy(startMs = 0L, endMs = 4_000L))),
                originalDate = date, exportMode = ExportMode.FAST_ONLY) { saved, report ->
                assertEquals(ExportMethod.ORIGINAL_COPY, report.method)
                MediaReader.validateExport(context, saved, 4_000L, true, date.epochMs)
                assertMiddleCopied(context, source, saved, 0L, 2_000_000L, 0L, 1_000L)
                assertDurationAndAudio(context, saved, 4_000L)
            }
        }
    }

    @Test
    fun overlappingAudibleRangesKeepTheirOwnTonesAndPictures() = runBlocking {
        withSource("quickcut_bframes.mp4") { context, source, original ->
            val timeline = EditTimeline(listOf(original.copy(startMs = 6_000L, endMs = 8_000L),
                original.copy(id = 2L, startMs = 5_000L, endMs = 8_000L)))
            withExport(context, timeline) { saved, report ->
                assertEquals(ExportMethod.PARTIAL_REENCODE, report.method)
                assertDurationAndAudio(context, saved, 5_000L, mapOf(1 to 1100, 9 to 880, 14 to 1100))
                assertTrue(meanDifference(frame(context, source, 5_000_000L),
                    frame(context, saved, 2_000_000L)) < 10.0)
                assertDecodedPictures(context, saved, 150)
            }
        }
    }

    @Test
    fun originalDateDoesNotForceFullVideoEncoding() = runBlocking {
        withSource("quickcut_bframes.mp4") { context, source, original ->
            val date = OriginalTimestamp(1_609_459_200_000L, "fixture")
            withExport(context, EditTimeline(listOf(original.copy(startMs = 3_000L, endMs = 7_500L))),
                originalDate = date) { saved, report ->
                assertEquals(ExportMethod.PARTIAL_REENCODE, report.method)
                MediaReader.validateExport(context, saved, 4_500L, true, date.epochMs)
                assertMiddleCopied(context, source, saved, 4_000_000L, 6_000_000L, 3_000_000L, 1_000L)
                assertDurationAndAudio(context, saved, 4_500L)
            }
        }
    }

    @Test
    fun rotatedSourceKeepsOrientationWithoutFullVideoEncoding() = runBlocking {
        withSource("quickcut_rotated.mp4") { context, source, original ->
            fun rotation(uri: Uri): String? = MediaMetadataRetriever().let { reader ->
                try { reader.setDataSource(context, uri); reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) }
                finally { reader.release() }
            }
            assertEquals("90", rotation(source))
            withExport(context, EditTimeline(listOf(original.copy(startMs = 3_000L, endMs = 7_500L)))) { saved, report ->
                assertEquals(ExportMethod.PARTIAL_REENCODE, report.method)
                assertEquals(rotation(source), rotation(saved))
                for (time in listOf(0L, 1_000_000L, 3_000_000L)) {
                    assertTrue(meanDifference(frame(context, source, time + 3_000_000L),
                        frame(context, saved, time)) < 12.0)
                }
                assertMiddleCopied(context, source, saved, 4_000_000L, 6_000_000L, 3_000_000L, 1_000L)
                assertDurationAndAudio(context, saved, 4_500L)
            }
        }
    }

    @Test
    fun hevcBFramesKeepExactCutsAndCopyTheMiddle() = runBlocking {
        withSource("quickcut_hevc.mp4") { context, source, original ->
            withExport(context, EditTimeline(listOf(original.copy(startMs = 3_000L, endMs = 7_500L)))) { saved, report ->
                assertEquals(ExportMethod.PARTIAL_REENCODE, report.method)
                assertEquals(null, report.optimizationResult)
                assertMiddleCopied(context, source, saved, 4_000_000L, 6_000_000L, 3_000_000L, 1_000L)
                for (time in listOf(0L, 1_000_000L, 2_966_667L, 3_000_000L, 4_466_667L)) {
                    assertTrue(meanDifference(frame(context, source, time + 3_000_000L), frame(context, saved, time)) < 12.0)
                }
                assertDurationAndAudio(context, saved, 4_500L)
                assertDecodedPictures(context, saved, 135)
            }
        }
    }

    @Test
    fun hevcDiscardPriorPicturesAtAJoinKeepsPartialProcessing() = runBlocking {
        withSource("quickcut_hevc_no_output.mp4") { context, source, original ->
            assertDecodedPictures(context, source, 240)
            val timeline = EditTimeline(listOf(original.copy(startMs = 6_000L, endMs = 8_000L),
                original.copy(id = 2L, startMs = 0L, endMs = 3_000L)))
            withExport(context, timeline) { saved, report ->
                assertEquals(ExportMethod.PARTIAL_REENCODE, report.method)
                assertEquals(FastExportReason.MULTIPLE_CLIPS, report.diagnostics.copyReason)
                assertEquals(null, report.diagnostics.smartCutReason)
                assertDecodedPictures(context, saved, 150)
                assertDurationAndAudio(context, saved, 5_000L)
                for (time in listOf(0L, 1_966_667L, 2_000_000L, 3_966_667L, 4_966_667L)) {
                    val sourceTime = if (time < 2_000_000L) time + 6_000_000L else time - 2_000_000L
                    assertTrue(meanDifference(frame(context, source, sourceTime), frame(context, saved, time)) < 12.0)
                }
            }
        }
    }

    @Test
    fun stereoMp3ReencodesOnlySoundWhenVideoCanBeCopied() = runBlocking {
        withSource("quickcut_mp3.mp4") { context, source, original ->
            val timeline = EditTimeline(listOf(original.copy(startMs = 0L, endMs = 2_000L),
                original.copy(id = 2L, startMs = 4_000L, endMs = 6_000L)))
            withExport(context, timeline) { saved, report ->
                assertEquals(ExportMethod.AUDIO_REENCODE, report.method)
                assertAudioFormat(context, saved, 2)
                assertMiddleCopied(context, source, saved, 0L, 2_000_000L, 0L, 1_000L)
                assertMiddleCopied(context, source, saved, 4_000_000L, 6_000_000L, 2_000_000L, 1_000L)
                assertDurationAndAudio(context, saved, 4_000L)
                assertDecodedPictures(context, saved, 120)
            }
        }
    }

    @Test
    fun ac3SurroundKeepsSixChannelsWithPartialVideoProcessing() = runBlocking {
        withSource("quickcut_ac3.mp4") { context, source, original ->
            withExport(context, EditTimeline(listOf(original.copy(startMs = 3_000L, endMs = 7_500L)))) { saved, report ->
                assertEquals(ExportMethod.PARTIAL_REENCODE, report.method)
                assertAudioFormat(context, saved, 6)
                assertMiddleCopied(context, source, saved, 4_000_000L, 6_000_000L, 3_000_000L, 1_000L)
                assertDurationAndAudio(context, saved, 4_500L)
                assertDecodedPictures(context, saved, 135)
            }
        }
    }

    @Test
    fun main10SdrUsesPartialProcessingAndPreservesColor() = assertTenBitExport("sdr", MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)

    @Test
    fun hdr10UsesPartialProcessingAndPreservesColor() = assertTenBitExport("pq", MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_TRANSFER_ST2084)

    @Test
    fun hlgUsesPartialProcessingAndPreservesColor() = assertTenBitExport("hlg", MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_TRANSFER_HLG)

    private fun assertTenBitExport(variant: String, standard: Int, transfer: Int) = runBlocking {
        withSource("quickcut_main10_$variant.mp4") { context, source, original ->
            val main10Profiles = setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
            fun checkColor(uri: Uri) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, uri, null)
                    val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                        .first { it.getString(MediaFormat.KEY_MIME) == "video/hevc" }
                    assertTrue("The extractor must identify a Main10 profile",
                        format.getInteger(MediaFormat.KEY_PROFILE) in main10Profiles)
                    assertEquals(standard, format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
                    assertEquals(transfer, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
                    assertEquals(MediaFormat.COLOR_RANGE_LIMITED, format.getInteger(MediaFormat.KEY_COLOR_RANGE))
                } finally { extractor.release() }
            }
            checkColor(source)
            val timeline = EditTimeline(listOf(original.copy(startMs = 3_000L, endMs = 7_500L)))
            val hevcDecoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals("video/hevc", ignoreCase = true) } }
                .associate { it.name to it.getCapabilitiesForType("video/hevc").profileLevels.map { level -> level.profile } }
            val main10Decoders = hevcDecoders.filterValues { profiles -> profiles.any { it in main10Profiles } }
            Log.i("QuickCutMain10Test", "$variant HEVC decoder profiles: $hevcDecoders")
            if (main10Decoders.isNotEmpty()) {
                withExport(context, timeline) { saved, report ->
                    persistTenBitFixtures(context, variant, source, saved)
                    assertEquals(ExportMethod.PARTIAL_REENCODE, report.method)
                    assertEquals(null, report.optimizationResult)
                    assertEquals(null, report.diagnostics.smartCutReason)
                    if (variant != "sdr") assertEquals(FastExportReason.COPY_COLOR_FORMAT, report.diagnostics.copyReason)
                    checkColor(saved)
                    assertMiddleCopied(context, source, saved, 4_000_000L, 6_000_000L, 3_000_000L, 1_000L)
                    assertDurationAndAudio(context, saved, 4_500L)
                    assertDecodedPictures(context, saved, 135)
                    Log.i("QuickCutMain10Test", "$variant passed full Android export and frame decoding")
                }
            } else {
                // API 35's x86_64 emulator exposes only 8-bit HEVC decoders. Its
                // rejection of Main10 source pictures is not an export failure.
                // Exercise the packaged native engine and require the host gate
                // to decode the actual Android artifacts saved below. This does
                // not claim Android Main10 playback coverage on this device.
                assertFalse("This path requires absent Main10 decoder capability: $hevcDecoders",
                    hevcDecoders.values.flatten().any { it in main10Profiles })
                val output = File(context.cacheDir, "main10_${variant}_${UUID.randomUUID()}.mp4")
                var unavailable: FastExportReason? = null
                try {
                    val result = NativeSmartCutTrimmer.tryExport(context, timeline, output, {},
                        onUnavailable = { unavailable = it })
                    val counts = result ?: throw AssertionError("Native Main10 export declined: $unavailable")
                    val saved = Uri.fromFile(output)
                    persistTenBitFixtures(context, variant, source, saved)
                    assertEquals(null, unavailable)
                    assertEquals(60L, counts.copiedPackets)
                    assertEquals(75L, counts.encodedPackets)
                    checkColor(saved)
                    assertMiddleCopied(context, source, saved, 4_000_000L, 6_000_000L, 3_000_000L, 1_000L)
                    val samples = videoSamples(context, saved).sortedBy { it.timeUs }
                    assertEquals("Every retained picture needs an output access unit", 135, samples.size)
                    samples.forEachIndexed { index, sample ->
                        assertTrue("Output access unit $index has unexpected PTS ${sample.timeUs}",
                            abs(sample.timeUs - index * 1_000_000L / 30L) <= 1_000L)
                    }
                    val metadata = MediaReader.metadata(context, saved)
                    assertTrue("Requested=4500ms, actual=${metadata.durationMs}ms",
                        abs(metadata.durationMs - 4_500L) <= 34L)
                    assertTrue("Native Main10 output must retain audio", metadata.hasAudio)
                    assertAudibleContinuousAudio(context, saved, 4_500L, emptyMap())
                    Log.i("QuickCutMain10Test", "$variant passed native export and extraction; " +
                        "Main10 playback unavailable on this device; host decoding is required")
                } finally {
                    output.delete()
                }
            }
        }
    }

    /** Keep actual device outputs after Gradle uninstalls the tested application. */
    private fun persistTenBitFixtures(context: Context, variant: String, source: Uri, output: Uri) {
        require(variant in setOf("sdr", "pq", "hlg"))
        val staging = File(requireNotNull(context.getExternalFilesDir(null)),
            "main10_validation_${UUID.randomUUID()}")
        assertTrue("External staging directory must be writable", staging.mkdirs())
        fun shell(command: String): String {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        try {
            shell("mkdir -p /data/local/tmp/quickcut-main10")
            for ((kind, uri) in listOf("source" to source, "output" to output)) {
                val staged = File(staging, "$variant-$kind.mp4")
                checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    staged.outputStream().use { input.copyTo(it) }
                }
                assertTrue("The preserved fixture must contain media bytes", staged.length() > 0L)
                // Generated paths contain no shell syntax or whitespace. The
                // shell cannot read the application's private cache directory.
                require(staged.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
                val destination = "/data/local/tmp/quickcut-main10/${staged.name}"
                shell("cp ${staged.absolutePath} $destination")
                val expectedHash = MessageDigest.getInstance("SHA-256").digest(staged.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val actualHash = shell("sha256sum $destination").trim().substringBefore(' ')
                assertEquals("The host decoder requires the complete actual Android fixture: $destination",
                    expectedHash, actualHash)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun assertAudioFormat(context: Context, uri: Uri, channels: Int) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME))
            assertEquals(channels, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        } finally { extractor.release() }
    }

    @Test
    fun shortRangeReportsWhyNoMiddleCanBeCopiedBeforeFallback() = runBlocking {
        withSource("quickcut_keyframes.mp4") { context, _, original ->
            val output = File(context.cacheDir, "quickcut_reason_${UUID.randomUUID()}.mp4")
            var reason: FastExportReason? = null
            try {
                val result = NativeSmartCutTrimmer.tryExport(context,
                    EditTimeline(listOf(original.copy(startMs = 3_000L, endMs = 3_500L))),
                    output, {}, onUnavailable = { reason = it })
                assertEquals(null, result)
                assertEquals(FastExportReason.NO_COPY_SEGMENT, reason)
                assertFalse(output.exists())
            } finally { output.delete() }
        }
    }

    @Test
    fun copyableJoinedRangesEncodeOnlyAudio() = runBlocking {
        withSource("quickcut_bframes.mp4") { context, source, original ->
            val timeline = EditTimeline(listOf(original.copy(startMs = 0L, endMs = 2_000L),
                original.copy(id = 2L, startMs = 4_000L, endMs = 6_000L)))
            withExport(context, timeline) { saved, report ->
                assertEquals(ExportMethod.AUDIO_REENCODE, report.method)
                assertEquals(null, report.optimizationResult)
                assertMiddleCopied(context, source, saved, 0L, 2_000_000L, 0L, 1_000L)
                assertMiddleCopied(context, source, saved, 4_000_000L, 6_000_000L, 2_000_000L, 1_000L)
                assertDurationAndAudio(context, saved, 4_000L)
            }
        }
    }

    @Test
    fun joinedRangesKeepBothCutsAndCopyGopsAcrossTheJoin() = runBlocking {
        withSource("quickcut_bframes.mp4") { context, source, original ->
            val timeline = EditTimeline(listOf(
                original.copy(startMs = 0L, endMs = 3_000L),
                original.copy(id = 2L, startMs = 4_500L, endMs = 8_000L),
            ))
            withExport(context, timeline) { saved, report ->
                assertEquals("Joined ranges must use partial video processing",
                    ExportMethod.PARTIAL_REENCODE, report.method)
                assertEquals(null, report.optimizationResult)
                assertDurationAndAudio(context, saved, 6_500L)
                assertMiddleCopied(context, source, saved, 0L, 2_000_000L,
                    requestedStartUs = 0L, timestampToleranceUs = 1_000L)
                assertMiddleCopied(context, source, saved, 6_000_000L, 8_000_000L,
                    requestedStartUs = 1_500_000L, timestampToleranceUs = 1_000L)
                listOf(0L to 0L, 2_966_667L to 2_966_667L,
                    3_000_000L to 4_500_000L, 4_500_000L to 6_000_000L,
                    6_466_667L to 7_966_667L).forEach { (outputUs, sourceUs) ->
                    assertTrue("Pictures on both sides of the removed middle must decode",
                        meanDifference(frame(context, source, sourceUs),
                            frame(context, saved, outputUs)) < 10.0)
                }
            }
        }
    }

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
    fun reorderedBFramesKeepTheirCopiedMiddleAndDecodeAcrossBothJoins() = runBlocking {
        withSource("quickcut_bframes.mp4") { context, source, original ->
            val requested = original.copy(startMs = 3_000L, endMs = 7_500L)
            withExport(context, EditTimeline(listOf(requested))) { saved, report ->
                assertEquals(ExportMethod.PARTIAL_REENCODE, report.method)
                assertEquals("This external B-frame input must use the native engine", null,
                    report.optimizationResult)
                assertMiddleCopied(context, source, saved, 4_000_000L, 6_000_000L,
                    requestedStartUs = 3_000_000L, timestampToleranceUs = 1_000L)
                listOf(0L, 1_000_000L, 2_966_667L, 3_000_000L, 4_466_667L).forEach { timeUs ->
                    assertTrue("Reordered pictures must decode at the requested moment: $timeUs",
                        meanDifference(frame(context, source, timeUs + 3_000_000L),
                            frame(context, saved, timeUs)) < 10.0)
                }
                assertDurationAndAudio(context, saved, 4_500L)
            }
        }
    }

    @Test
    fun mediaStoreContentUriUsesNativePartialProcessing() = runBlocking {
        withSource("quickcut_keyframes.mp4") { context, source, original ->
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "quickcut_native_source_${System.nanoTime()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/QuickCut")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val imported = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
            try {
                checkNotNull(resolver.openInputStream(source)).use { input ->
                    checkNotNull(resolver.openOutputStream(imported)).use { input.copyTo(it) }
                }
                resolver.update(imported, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
                val requested = original.copy(uri = imported.toString(), startMs = 3_000L, endMs = 7_500L)
                withExport(context, EditTimeline(listOf(requested))) { saved, report ->
                    assertEquals(ExportMethod.PARTIAL_REENCODE, report.method)
                    assertEquals(null, report.optimizationResult)
                    assertMiddleCopied(context, imported, saved, 4_000_000L, 6_000_000L,
                        requestedStartUs = 3_000_000L, timestampToleranceUs = 1_000L)
                    assertDurationAndAudio(context, saved, 4_500L)
                }
            } finally {
                resolver.delete(imported, null, null)
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
        withSource("quickcut_exact_boundary.mp4") { context, source, original ->
            val timeline = EditTimeline(listOf(
                original.copy(startMs = 500L, endMs = 1_500L),
                original.copy(id = 2L, startMs = 2_500L, endMs = 3_500L),
            ))
            val unchanged = timeline.copy(clips = timeline.clips.map { it.copy() })
            withExport(context, timeline) { saved, report ->
                // Collect both streams before a video/audio assertion can stop this test.
                fallbackAudioEvidence(context, source, saved)
                assertColor(context, saved, 0L, red = true)
                assertColor(context, saved, 900_000L, red = true)
                assertColor(context, saved, 1_100_000L, red = false)
                assertFallbackVideoAndAudioBoundaries(context, saved)
                assertEquals("Unsupported compositions must retain the normal full fallback",
                    ExportMethod.VIDEO_REENCODE, report.method)
                assertEquals(unchanged, timeline)
            }
        }
    }

    @Test
    fun mutedNativePartialExportKeepsTheCopiedMiddleAndRemovesTheAudioTrack() = runBlocking {
        withSource("quickcut_keyframes.mp4") { context, source, original ->
            val requested = original.copy(startMs = 3_000L, endMs = 7_500L, muted = true)
            withExport(context, EditTimeline(listOf(requested))) { saved, report ->
                val metadata = MediaReader.validateExport(context, saved, 4_500L, expectAudio = false)
                assertTrue(abs(metadata.durationMs - 4_500L) <= 34L)
                assertFalse("Mute must remove the source AAC track, not retain silent metadata", metadata.hasAudio)
                assertEquals(ExportMethod.PARTIAL_REENCODE, report.method)
                assertEquals("This fixture must exercise native partial encoding", null, report.optimizationResult)
                assertMiddleCopied(context, source, saved, 4_000_000L, 6_000_000L,
                    requestedStartUs = 3_000_000L, timestampToleranceUs = 1_000L)
                assertTrue("Muted output must keep the chosen first video frame",
                    meanDifference(frame(context, source, 3_000_000L), frame(context, saved, 0L)) < 10.0)
                assertTrue(requested.muted)
            }
        }
    }

    @Test
    fun cancelledJobAtJniEntryReturnsCancellationWithoutCreatingAnOutput() = runBlocking {
        withSource("quickcut_keyframes.mp4") { context, source, _ ->
            // This deliberately tests the packaged JNI/coroutine cancellation
            // boundary, not in-flight progress timing on a short test fixture.
            System.loadLibrary("quickcut_smartcut")
            val destination = File(context.cacheDir, "smartcut_precancel_${UUID.randomUUID()}.mp4")
            val job = Job().apply { cancel() }
            val callbacks = NativeSmartCutCallbacks(job) {
                throw AssertionError("An already cancelled native request must not deliver progress")
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    NativeSmartCutBridge.cut(source.path!!, destination.absolutePath,
                        3_000_000L, 7_500_000L, false, callbacks)
                }
                assertArrayEquals("JNI must distinguish cancellation from an unsupported-input fallback",
                    longArrayOf(-1L, 0L, 0L), result)
                assertFalse("An already cancelled request must not leave a staged output", destination.exists())
            } finally {
                callbacks.close()
                destination.delete()
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
            assertTrue("The source fixture must contain audio", metadata.hasAudio)
            block(context, uri, VideoClip(1L, uri.toString(), metadata.durationMs,
                hasAudio = metadata.hasAudio))
        } finally {
            file.delete()
        }
    }

    private suspend fun withExport(
        context: Context,
        timeline: EditTimeline,
        originalDate: OriginalTimestamp? = null,
        exportMode: ExportMode = ExportMode.ALLOW_REENCODE,
        block: suspend (Uri, ExportReport) -> Unit,
    ) {
        val outputs = mutableListOf<Uri>()
        var committed: Uri? = null
        var commitCount = 0
        var reportCount = 0
        var report: ExportReport? = null
        var observedDiagnostics = ExportDiagnostics()
        try {
            val saved = VideoExporter(context).export(
                timeline, {}, {},
                saveDateMode = if (originalDate == null) SaveDateMode.CURRENT else SaveDateMode.ORIGINAL,
                resolvedOriginalDate = originalDate,
                exportMode = exportMode,
                onOutputCreated = { outputs.add(it) },
                onCommitted = { committed = it; commitCount++ },
                onDiagnostics = {
                    assertEquals("Show reasons before publication", null, committed)
                    assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                    observedDiagnostics = it
                },
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
            assertEquals("Progress and completion must retain the same observed reasons",
                observedDiagnostics, report?.diagnostics)
            val pending = context.contentResolver.query(saved,
                arrayOf(MediaStore.Video.Media.IS_PENDING), null, null, null)?.use {
                if (it.moveToFirst()) it.getInt(0) else -1
            }
            assertEquals("The saved video must be published", 0, pending)
            block(saved, report ?: throw AssertionError("A committed export requires a processing report"))
        } catch (failure: Throwable) {
            SmartCutFailureDiagnostics.capture(context, "native", timeline, committed ?: outputs.lastOrNull(), failure)
            throw failure
        } finally {
            outputs.distinct().forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun fallbackAudioEvidence(context: Context, source: Uri, output: Uri) {
        runCatching { persistFallbackFixtures(context, source, output) }
            .onFailure { Log.e("FallbackAudioEvidence", "Could not preserve fixtures", it) }
        for ((label, uri, ranges) in listOf(
            Triple("source", source, listOf(500_000L until 1_500_000L, 2_500_000L until 3_500_000L)),
            Triple("output", output, listOf(0L until 1_000_000L, 1_000_000L until 2_000_000L)),
        )) {
            runCatching { decodedAudioEvidence(context, label, uri, ranges) }
                .onFailure { Log.e("FallbackAudioEvidence", "$label decode failed", it) }
        }
    }

    /** Preserve real files before Gradle uninstalls the app, without changing export behavior. */
    private fun persistFallbackFixtures(context: Context, source: Uri, output: Uri) {
        val id = UUID.randomUUID().toString()
        val staging = File(requireNotNull(context.getExternalFilesDir(null)), "fallback_$id")
        check(staging.mkdirs())
        fun shell(command: String): String {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        try {
            val destination = "/data/local/tmp/quickcut-native-diagnostics/fallback_$id"
            shell("mkdir -p $destination")
            for ((label, uri) in listOf("source" to source, "output" to output)) {
                val staged = File(staging, "$label.mp4")
                checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    staged.outputStream().use { input.copyTo(it) }
                }
                require(staged.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
                shell("cp ${staged.absolutePath} $destination/${staged.name}")
                val expected = MessageDigest.getInstance("SHA-256").digest(staged.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val actual = shell("sha256sum $destination/${staged.name}").trim().substringBefore(' ')
                check(expected == actual) { "Incomplete preserved $label fixture" }
                Log.i("FallbackAudioEvidence", "saved=$destination/${staged.name} sha256=$actual")
            }
        } finally { staging.deleteRecursively() }
    }

    /** Diagnostics only: observe actual decoder trimming, sample clocks and retained source PCM. */
    private fun decodedAudioEvidence(context: Context, label: String, uri: Uri, ranges: List<LongRange>) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val format = extractor.getTrackFormat(track)
            Log.i("FallbackAudioEvidence", "$label input=$format")
            extractor.selectTrack(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var frames = 0L
            var firstUs: Long? = null
            var endUs: Long? = null
            var firstSignalUs: Long? = null
            var lastSignalUs: Long? = null
            var minGapUs = 0L
            var maxGapUs = 0L
            val rangeFrames = LongArray(ranges.size)
            val packetTimes = mutableListOf<Long>()
            val deadline = SystemClock.elapsedRealtime() + 20_000L
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline) { "Audio evidence decode timed out" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(5_000L)
                    if (index >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(index))
                        buffer.clear()
                        val size = if (extractor.sampleTrackIndex >= 0) extractor.readSampleData(buffer, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            if (packetTimes.size < 320) packetTimes.add(extractor.sampleTime)
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 5_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val actual = codec.outputFormat
                        Log.i("FallbackAudioEvidence", "$label decodedFormat=$actual")
                        rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        check(!actual.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                            actual.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                if (firstUs == null) firstUs = info.presentationTimeUs
                                endUs?.let { previous ->
                                    val gapUs = info.presentationTimeUs - previous
                                    minGapUs = minOf(minGapUs, gapUs)
                                    maxGapUs = maxOf(maxGapUs, gapUs)
                                }
                                val buffer = checkNotNull(codec.getOutputBuffer(index)).duplicate()
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val count = info.size / (2 * channels)
                                repeat(count) { frame ->
                                    val timeUs = info.presentationTimeUs + frame * 1_000_000L / rate
                                    var amplitude = 0
                                    repeat(channels) { amplitude = maxOf(amplitude, abs(buffer.short.toInt())) }
                                    if (amplitude > 100) {
                                        if (firstSignalUs == null) firstSignalUs = timeUs
                                        lastSignalUs = timeUs
                                    }
                                    ranges.forEachIndexed { range, bounds ->
                                        if (timeUs in bounds) rangeFrames[range]++
                                    }
                                }
                                frames += count
                                endUs = info.presentationTimeUs + count * 1_000_000L / rate
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
            packetTimes.chunked(40).forEachIndexed { chunk, pts ->
                Log.i("FallbackAudioEvidence", "$label packetPts[$chunk]=$pts")
            }
            Log.i("FallbackAudioEvidence", "$label pcmFrames=$frames rate=$rate channels=$channels " +
                "firstUs=$firstUs endUs=$endUs firstSignalUs=$firstSignalUs lastSignalUs=$lastSignalUs " +
                "minGapUs=$minGapUs maxGapUs=$maxGapUs ranges=$ranges rangeFrames=${rangeFrames.toList()}")
        } finally {
            try { decoder?.release() } finally { extractor.release() }
        }
    }

    /** This fixture retains exactly 30 red pictures followed by 30 blue pictures. */
    private fun assertFallbackVideoAndAudioBoundaries(context: Context, uri: Uri) {
        val expectedUs = 2_000_000L
        val extractor = MediaExtractor()
        val formats: List<MediaFormat>
        val times: List<MutableList<Long>>
        try {
            extractor.setDataSource(context, uri, null)
            formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            times = formats.map { mutableListOf<Long>() }
            formats.indices.forEach { extractor.selectTrack(it) }
            while (extractor.sampleTrackIndex >= 0) {
                times[extractor.sampleTrackIndex].add(extractor.sampleTime)
                if (!extractor.advance()) break
            }
        } finally {
            extractor.release()
        }
        // Keep bounded clock evidence in logcat: Gradle can uninstall the app before
        // the runner retrieves the separate failure-diagnostics files.
        formats.forEachIndexed { track, format ->
            val pts = times[track].sorted()
            val steps = pts.zipWithNext { a, b -> b - a }
            Log.i("FallbackBoundaryCheck", "track=$track format=$format samples=${pts.size} " +
                "firstUs=${pts.firstOrNull()} lastUs=${pts.lastOrNull()} " +
                "minStepUs=${steps.minOrNull()} maxStepUs=${steps.maxOrNull()}")
        }
        val video = formats.indices.single {
            formats[it].getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        val videoTimes = times[video].sorted()
        assertEquals("Fallback must retain exactly both one-second video ranges", 60, videoTimes.size)
        videoTimes.forEachIndexed { index, timeUs ->
            assertTrue("Fallback video picture $index moved: $timeUs us",
                abs(timeUs - index * 1_000_000L / 30L) <= 1_000L)
        }
        assertTrue("Video track duration must remain exactly two seconds",
            abs(formats[video].getLong(MediaFormat.KEY_DURATION) - expectedUs) <= 1_000L)
        assertDecodedPictures(context, uri, 60)
        assertColor(context, uri, 966_667L, red = true)
        assertColor(context, uri, 1_000_000L, red = false)
        assertColor(context, uri, 1_966_667L, red = false)

        val audio = formats.indices.single {
            formats[it].getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        assertEquals("Only AAC encoding permits this padding allowance", "audio/mp4a-latm",
            formats[audio].getString(MediaFormat.KEY_MIME))
        val sampleRate = formats[audio].getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val audioTimes = times[audio].sorted()
        val audioSteps = audioTimes.zipWithNext { a, b -> b - a }
        assertTrue("The fallback must emit AAC packets", audioSteps.isNotEmpty())
        val aacFrameUs = 1_024_000_000L / sampleRate
        assertTrue("The padding budget requires measured 1024-sample AAC access units",
            audioSteps.all { abs(it - aacFrameUs) <= 1L })
        val measuredFrameUs = audioSteps.max()
        val delaySamples = if (formats[audio].containsKey(MediaFormat.KEY_ENCODER_DELAY))
            formats[audio].getInteger(MediaFormat.KEY_ENCODER_DELAY) else 0
        val delayUs = delaySamples * 1_000_000L / sampleRate
        assertTrue("AAC must begin at zero after its declared encoder delay: first=${audioTimes.first()} delay=$delayUs",
            abs(audioTimes.first() + delayUs) <= 1_000L)
        val audioEndUs = audioTimes.last() + measuredFrameUs
        assertTrue("AAC must cover both ranges with at most priming and final-frame padding",
            audioEndUs in (expectedUs - 1_000L)..(expectedUs + 2 * measuredFrameUs))
        // The container can include one encoder-priming access unit and the final
        // partially filled AAC unit. Video timing above has no such allowance.
        val metadata = MediaReader.validateExport(context, uri, 2_000L, expectAudio = true)
        Log.i("FallbackBoundaryCheck", "containerMs=${metadata.durationMs} " +
            "audioEndUs=$audioEndUs measuredAacFrameUs=$measuredFrameUs")
        val paddingMs = (2 * measuredFrameUs + 999L) / 1_000L
        assertTrue("Container duration must be explained by measured AAC framing: ${metadata.durationMs} ms",
            metadata.durationMs in 2_000L..(2_000L + paddingMs))
        assertAudibleContinuousAudio(context, uri, 2_000L, mapOf(1 to 440, 6 to 440))
    }

    private fun assertDurationAndAudio(context: Context, uri: Uri, expectedMs: Long, tones: Map<Int, Int> = emptyMap()) {
        val metadata = MediaReader.validateExport(context, uri, expectedMs, expectAudio = true)
        assertTrue("Requested=${expectedMs}ms, actual=${metadata.durationMs}ms",
            abs(metadata.durationMs - expectedMs) <= 34L)
        assertAudibleContinuousAudio(context, uri, expectedMs, tones)
    }

    private data class VideoSample(val timeUs: Long, val vcl: List<String>)

    private fun videoSamples(context: Context, uri: Uri): List<VideoSample> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) in setOf("video/avc", "video/hevc")
            }
            val hevc = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME) == "video/hevc"
            extractor.selectTrack(track)
            return buildList {
                while (extractor.sampleTrackIndex >= 0) {
                    val buffer = ByteBuffer.allocate(extractor.sampleSize.toInt())
                    assertEquals(buffer.capacity(), extractor.readSampleData(buffer, 0))
                    val hashes = nalUnits(buffer.array()).filter { nal ->
                        if (hevc) ((nal[0].toInt() ushr 1) and 0x3f) <= 31
                        else (nal[0].toInt() and 0x1f) in 1..5
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
            val rotated = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) in setOf("90", "270")
            val width = if (rotated) 120 else 160
            val height = if (rotated) 160 else 120
            val bitmap = retriever.getScaledFrameAtTime(timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST, width, height)
                ?: throw AssertionError("Frame at ${timeUs}us must decode")
            return try {
                assertEquals(width, bitmap.width)
                assertEquals(height, bitmap.height)
                IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
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

    private fun assertDecodedPictures(context: Context, uri: Uri, expectedFrames: Int) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            var inputEnded = false
            var outputEnded = false
            val times = mutableListOf<Long>()
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            while (!outputEnded) {
                assertTrue("Full video decode must terminate", SystemClock.elapsedRealtime() < deadline)
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000L)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000L)
                if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) times.add(info.presentationTimeUs)
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            assertEquals("Every retained picture must actually decode", expectedFrames, times.size)
            times.forEachIndexed { frame, time ->
                assertTrue("Decoded picture $frame must retain its output timestamp: $time",
                    abs(time - frame * 1_000_000L / 30L) <= 1_000L)
            }
        } finally {
            decoder?.release()
            extractor.release()
        }
    }

    private fun assertAudibleContinuousAudio(context: Context, uri: Uri, expectedMs: Long, tones: Map<Int, Int>) {
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
            var lastSample = 0
            var crossings = 0
            val checkedTones = mutableSetOf<Int>()
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
                                val sample = buffer.short.toInt()
                                if ((sample >= 0) != (lastSample >= 0)) crossings++
                                lastSample = sample
                                windowAmplitude += abs(sample).toLong()
                                windowSamples++
                                if (windowSamples >= sampleRate.toLong() * channels / 4) {
                                    assertTrue("Each 250ms audio window must retain the audible fixture tone",
                                        windowAmplitude / windowSamples > 100L)
                                    tones[windows]?.let { expected ->
                                        assertEquals("Tone fixture is mono", 1, channels)
                                        val actual = crossings.toDouble() * sampleRate / (2 * windowSamples)
                                        assertTrue("Window $windows must contain the selected source tone: expected=$expected actual=$actual",
                                            abs(actual - expected) <= 25.0)
                                        checkedTones.add(windows)
                                    }
                                    windows++
                                    crossings = 0
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
            assertEquals("Every expected source tone must be checked", tones.keys, checkedTones)
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
