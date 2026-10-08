package com.hebi.quickcut.media

import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.batch.BatchPlan
import com.hebi.quickcut.batch.BatchTemplate
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchDateExportInstrumentedTest {
    private val expectedOriginalDate = Instant.parse("2021-01-01T00:00:00Z").toEpochMilli()

    private suspend fun exportFixture(dateMode: SaveDateMode, verify: (Uri) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "quickcut_batch_date_input.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("quickcut_fixture.mp4").use { source ->
                input.outputStream().use { target -> source.copyTo(target) }
            }
        try {
            val uri = Uri.fromFile(input)
            val metadata = MediaReader.metadata(context, uri)
            val template = BatchTemplate(
                name = "앞뒤 반초 삭제",
                leadingMs = 500L,
                trailingMs = 500L,
            )
            val planned = template.plan(uri.toString(), metadata.durationMs, metadata.hasAudio)
            assertTrue("Batch template must create a clip", planned is BatchPlan.Ready)
            val timeline = (planned as BatchPlan.Ready).timeline
            assertEquals(2_000L, timeline.totalDurationMs)
            val output = withTimeout(120_000L) {
                VideoExporter(context).export(
                    timeline = timeline,
                    onProgress = {},
                    onFinalizing = {},
                    saveDateMode = dateMode,
                    outputBaseName = "test_original_video.mp4",
                )
            }
            try {
                assertTrue(MediaReader.metadata(context, output).durationMs > 1_500L)
                verify(output)
                val displayName = context.contentResolver.query(
                    output, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
                )?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                assertNotNull(displayName)
                assertTrue(displayName!!.contains("test_original_video_QuickCut_"))
            } finally {
                context.contentResolver.delete(output, null, null)
            }
        } finally {
            input.delete()
        }
    }

    @Test
    fun originalDatePreservesVideoCreationDate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        exportFixture(SaveDateMode.ORIGINAL) { output ->
            val actual = OriginalMediaDates.resolve(context, output)
            assertNotNull("Cannot read the copied original capture date", actual)
            assertTrue(
                "Original date was not preserved: expected $expectedOriginalDate actual " +
                    actual!!.epochMs,
                abs(actual.epochMs - expectedOriginalDate) <= 3_000L,
            )
        }
    }

    @Test
    fun currentDateSavesAsNewVideo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = System.currentTimeMillis()
        exportFixture(SaveDateMode.CURRENT) { output ->
            val actual = OriginalMediaDates.resolve(context, output)
            assertNotNull("Cannot read the new save timestamp", actual)
            assertTrue(
                "Saved video should be dated today, got " + actual!!.epochMs,
                actual.epochMs in (before - 10_000L)..(System.currentTimeMillis() + 10_000L),
            )
        }
    }
}
