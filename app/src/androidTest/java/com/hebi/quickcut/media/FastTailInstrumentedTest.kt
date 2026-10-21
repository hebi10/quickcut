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
            Unit
        } finally { input.delete(); output.delete() }
    }
}
