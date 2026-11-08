package com.hebi.quickcut.media

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.AudioAdjustment
import com.hebi.quickcut.editor.CropGeometry
import com.hebi.quickcut.editor.CropRatio
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.TextLayer
import com.hebi.quickcut.editor.TextLayerColor
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage19CombinedExportInstrumentedTest {
    @Test fun allMediaEffectsCanExportTogetherWithoutChangingSource() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "stage19_combined_720p.mp4")
        instrumentation.context.assets.open("quickcut_quality_hd_720.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        var published: Uri? = null
        try {
            val sourceBytes = source.length()
            val uri = Uri.fromFile(source)
            val original = MediaReader.metadata(context, uri)
            assertTrue(original.hasAudio && original.durationMs >= 1_400)
            val crop = CropGeometry.centerForRatio(1280, 720, CropRatio.SQUARE)
            val text = TextLayer(
                1L, "STAGE19", 350L, 1_150L, 0.5f, 0.5f,
                size = 150, color = TextLayerColor.YELLOW,
            )
            val timeline = EditTimeline(listOf(VideoClip(
                id = 1L,
                uri = uri.toString(),
                sourceDurationMs = original.durationMs,
                startMs = 0L,
                endMs = original.durationMs,
                hasAudio = true,
                crop = crop,
                audio = AudioAdjustment(55, 250L, 250L),
                textLayers = listOf(text),
            )))
            val planning = ExportPlanner.analyze(context, timeline, SaveDateMode.CURRENT)
            assertTrue("Combined effects must not be fast-copied", !planning.fastAvailable)
            val settings = ExportQualitySettings(
                resolution = ExportResolution.HD_720,
                quality = ExportQualityLevel.LOW,
            )
            val check = ExportQualityInspector.inspect(context, timeline, settings)
            if (!check.supported) {
                assertTrue("Unsupported device must explain why",
                    !check.error.isNullOrBlank())
                return@runBlocking
            }
            val plan = requireNotNull(check.plan)
            published = VideoExporter(context).export(
                timeline, onProgress = {}, onFinalizing = {},
                exportMode = ExportMode.ALLOW_REENCODE,
                outputQuality = settings,
            )
            MediaReader.validateExport(context, requireNotNull(published),
                original.durationMs, expectAudio = true)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(context, requireNotNull(published))
                val w = requireNotNull(reader.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).toInt()
                val h = requireNotNull(reader.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)).toInt()
                assertTrue("Crop + quality output must be square: ${w}x${h}",
                    kotlin.math.abs(w - h) <= 4)
                assertTrue("Quality output must be 720px short side: ${w}x${h}",
                    kotlin.math.abs(minOf(w, h) - plan.shortSide) <= 16)
                val before = requireNotNull(reader.getScaledFrameAtTime(
                    100_000L, MediaMetadataRetriever.OPTION_CLOSEST, 320, 320))
                val during = requireNotNull(reader.getScaledFrameAtTime(
                    800_000L, MediaMetadataRetriever.OPTION_CLOSEST, 320, 320))
                val after = requireNotNull(reader.getScaledFrameAtTime(
                    1_350_000L, MediaMetadataRetriever.OPTION_CLOSEST, 320, 320))
                try {
                    val colors = listOf(before, during, after).map(::yellowCount)
                    assertTrue("Text only in selected interval: $colors",
                        colors[1] > colors[0] + 18 && colors[1] > colors[2] + 18)
                } finally { before.recycle(); during.recycle(); after.recycle() }
            } finally { reader.release() }
            assertEquals("Original MP4 must be kept intact", sourceBytes, source.length())
        } finally {
            published?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }

    private fun yellowCount(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { color ->
            Color.red(color) >= 190 && Color.green(color) >= 160 &&
                Color.blue(color) < 180
        }
    }
}
