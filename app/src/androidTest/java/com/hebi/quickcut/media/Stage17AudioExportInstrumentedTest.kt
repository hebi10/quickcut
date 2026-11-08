package com.hebi.quickcut.media

import android.net.Uri
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import java.security.MessageDigest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.AudioAdjustment
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.EditorSessionSnapshot
import com.hebi.quickcut.editor.EditorSessionStore
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage17AudioExportInstrumentedTest {
    @Test fun changingVolumeAndFadesRequiresConsentAndProducesAudibleMp4() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "stage17_sound_fixture.mp4")
        instrumentation.context.assets.open("quickcut_keyframes.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val sourceHash = sha256(source)
        var output: Uri? = null
        try {
            val uri = Uri.fromFile(source)
            val metadata = MediaReader.metadata(context, uri)
            assertTrue("Audio fixture must contain a real audio track", metadata.hasAudio)
            val duration = minOf(metadata.durationMs, 3_000L)
            val edit = AudioAdjustment(volumePercent = 50, fadeInMs = 1_000L,
                fadeOutMs = 1_000L)
            val timeline = EditTimeline(listOf(VideoClip(
                id = 1L, uri = uri.toString(), sourceDurationMs = metadata.durationMs,
                startMs = 0L, endMs = duration, hasAudio = true, audio = edit,
            )))
            val assessment = ExportPlanner.analyze(context, timeline, SaveDateMode.CURRENT)
            assertFalse(assessment.fastAvailable)
            assertTrue(assessment.explanation.contains("오디오 재인코딩"))
            val denied = runCatching {
                VideoExporter(context).export(timeline, {}, {}, exportMode = ExportMode.FAST_ONLY)
            }.exceptionOrNull()
            assertTrue("Unapproved audio conversion must be rejected: $denied",
                denied is ReencodeRequiredException)

            output = VideoExporter(context).export(
                timeline = timeline, onProgress = {}, onFinalizing = {},
                exportMode = ExportMode.ALLOW_REENCODE,
            )
            MediaReader.validateExport(context, requireNotNull(output), duration, true)
            saveSyntheticMp4Evidence(context, source, requireNotNull(output))
            val reference = AudioWaveformReader.read(context, uri, 0L, duration)
            assertTrue("Original source audio could not be decoded", reference != null)
            val waveform = AudioWaveformReader.read(context, requireNotNull(output), 0L, duration)
            assertTrue("Actual output audio waveform could not be decoded", waveform != null)
            val peaks = requireNotNull(waveform).peaks
            val middle = peaks.subList(14, 28).maxOrNull() ?: 0f
            val sourceMiddle = requireNotNull(reference).peaks.subList(14, 28).maxOrNull() ?: 0f
            assertTrue("Middle audio is missing", middle > 0.003f)
            assertTrue("Volume 50% was not applied: source=$sourceMiddle result=$middle",
                sourceMiddle > 0.003f && middle < sourceMiddle * 0.78f &&
                    middle > sourceMiddle * 0.20f)
            assertTrue("Fade-in is not present: first=${peaks.take(3)} middle=$middle",
                (peaks.take(3).maxOrNull() ?: 0f) < middle * 0.75f)
            assertTrue("Fade-out is not present: last=${peaks.takeLast(3)} middle=$middle",
                (peaks.takeLast(3).maxOrNull() ?: 0f) < middle * 0.75f)
            assertEquals("The source MP4 bytes changed", sourceHash, sha256(source))
        } finally {
            output?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun saveSyntheticMp4Evidence(context: Context, source: File, output: Uri) {
        val folder = File(context.filesDir, "stage20-audio-evidence")
        check(folder.isDirectory || folder.mkdirs())
        source.copyTo(File(folder, "stage17-source.mp4"), overwrite = true)
        val saved = File(folder, "stage17-output.mp4")
        requireNotNull(context.contentResolver.openInputStream(output)).use { input ->
            saved.outputStream().use { destination -> input.copyTo(destination) }
        }
        check(saved.length() > 0L)
        val detail = listOf(
            "sourceSha256=" + sha256(source),
            "outputSha256=" + sha256(saved),
            "source=" + inspectSyntheticAudio(context, Uri.fromFile(source)),
            "output=" + inspectSyntheticAudio(context, output),
        )
        File(folder, "stage17-audio-track.txt").writeText(detail.joinToString("\n") + "\n")
    }

    private fun inspectSyntheticAudio(context: Context, uri: Uri): String {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return "audioTrackMissing"
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME)
            extractor.selectTrack(index)
            val times = mutableListOf<Long>()
            repeat(12) {
                val time = extractor.sampleTime
                if (time != -1L) {
                    times += time
                    extractor.advance()
                }
            }
            "mime=$mime, sampleTimesUs=${times.joinToString(",")}"
        } catch (error: Exception) {
            "readError=${error.javaClass.simpleName}"
        } finally {
            extractor.release()
        }
    }

    @Test fun audioSettingsAndMuteRecoverWithoutChangingLegacyProjectData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EditorSessionStore(context, "quickcut_stage17_audio_test_only")
        try {
            val original = VideoClip(3, "content://local/audio", 10_000, hasAudio = true)
            val timeline = EditTimeline(listOf(original)).updateAudio(
                3, AudioAdjustment(65, 350L, 650L)
            ).toggleMute(3)
            val snapshot = EditorSessionSnapshot(timeline, 3, 2_000, quickMode = false)
            store.save(snapshot)
            assertEquals(snapshot, store.load())
            assertTrue(requireNotNull(store.load()).timeline.clips.single().muted)
            assertEquals(65, requireNotNull(store.load())
                .timeline.clips.single().audio.volumePercent)
        } finally {
            store.clear()
        }
    }

    @Test fun unityGainAtCrossingFadeBoundaryNeverReturnsUnsetRange() {
        val provider = ClipAudioEffects.gainProvider(AudioAdjustment(100, 500L, 500L), 1_000)
        val lastFadeInSample = 24_000L
        assertEquals(1f, provider.getGainFactorAtSamplePosition(lastFadeInSample, 48_000), 0.0001f)
        assertTrue("Unity range must advance at least one frame",
            provider.isUnityUntil(lastFadeInSample, 48_000) > lastFadeInSample)
        assertTrue("After the unity sample, fading must resume",
            provider.getGainFactorAtSamplePosition(lastFadeInSample + 1L, 48_000) < 1f)
    }

    @Test fun gainProviderFollowsSamplePositionAndNeverAmplifies() {
        val settings = AudioAdjustment(40, 500L, 500L)
        val provider = ClipAudioEffects.gainProvider(settings, 2_000)
        assertEquals(0f, provider.getGainFactorAtSamplePosition(0L, 48_000), 0.0001f)
        assertEquals(0.4f, provider.getGainFactorAtSamplePosition(48_000L, 48_000), 0.0001f)
        assertEquals(0f, provider.getGainFactorAtSamplePosition(96_000L, 48_000), 0.0001f)
    }
}
