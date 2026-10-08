package com.hebi.quickcut.media

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoExportInstrumentedTest {
    @Test
    fun trimmedVideoIsSavedAndPlayable() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val source = File(context.cacheDir, "quickcut_fixture.mp4")
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("quickcut_fixture.mp4").use { input ->
                    source.outputStream().use { output -> input.copyTo(output) }
                }
            assertTrue("The emulator fixture is missing", source.exists())

            val input = Uri.fromFile(source)
            val metadata = MediaReader.metadata(context, input)
            assertTrue("Expected an approximately three second video", metadata.durationMs >= 2_700L)

            val clip = VideoClip(
                id = 1,
                uri = input.toString(),
                sourceDurationMs = metadata.durationMs,
                startMs = 500L,
                endMs = 2_500L,
                hasAudio = metadata.hasAudio,
            )
            val outputUri = withTimeout(120_000L) {
                VideoExporter(context).export(
                    timeline = EditTimeline(listOf(clip)),
                    onProgress = {},
                    onFinalizing = {},
                )
            }
            try {
                val exported = MediaReader.metadata(context, outputUri)
                assertTrue("Trimmed video is unexpectedly short", exported.durationMs >= 1_200L)
                assertTrue("Trimmed video is unexpectedly long", exported.durationMs <= 3_200L)
            } finally {
                context.contentResolver.delete(outputUri, null, null)
                source.delete()
            }
        }
    }
}
