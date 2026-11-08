package com.hebi.quickcut.media

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage15OutputQualityInstrumentedTest {
    @Test fun supportedExplicitQualityExportsValidatedVideo() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "stage15_quality_export_source.mp4")
        instrumentation.context.assets.open("quickcut_keyframes.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        var published: Uri? = null
        try {
            val uri = Uri.fromFile(source)
            val meta = MediaReader.metadata(context, uri)
            val timeline = EditTimeline(listOf(
                VideoClip(1, uri.toString(), meta.durationMs, hasAudio = meta.hasAudio)
            ))
            val custom = ExportQualitySettings(quality = ExportQualityLevel.LOW)
            val evaluation = ExportQualityInspector.inspect(context, timeline, custom)
            if (!evaluation.supported) {
                assertTrue("Unsupported encoder must explain why", !evaluation.error.isNullOrBlank())
                return@runBlocking
            }
            val expected = requireNotNull(evaluation.plan)
            published = VideoExporter(context).export(
                timeline = timeline, onProgress = {}, onFinalizing = {},
                exportMode = ExportMode.ALLOW_REENCODE, outputQuality = custom,
            )
            val verified = MediaReader.validateExport(
                context, requireNotNull(published), meta.durationMs, meta.hasAudio
            )
            assertEquals(meta.hasAudio, verified.hasAudio)
            assertTrue(verified.durationMs >= 100L)
            assertTrue(expected.bitrate > 0)
        } finally {
            published?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }

    @Test fun supportedHdAndFullHdProfilesExportRealOutputResolution() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val profiles = listOf(
            "quickcut_quality_hd_720.mp4" to ExportResolution.HD_720,
            "quickcut_quality_fullhd_1080.mp4" to ExportResolution.FULL_HD_1080,
        )
        for ((fixture, resolution) in profiles) {
            val source = File(context.cacheDir, "quality_test_" + fixture)
            instrumentation.context.assets.open(fixture).use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
            var output: Uri? = null
            try {
                val sourceUri = Uri.fromFile(source)
                val info = MediaReader.metadata(context, sourceUri)
                val timeline = EditTimeline(listOf(
                    VideoClip(1, sourceUri.toString(), info.durationMs, hasAudio = info.hasAudio)
                ))
                val settings = ExportQualitySettings(
                    resolution = resolution, quality = ExportQualityLevel.LOW,
                )
                val evaluation = ExportQualityInspector.inspect(context, timeline, settings)
                if (!evaluation.supported) {
                    assertTrue("Unsupported hardware must provide reason",
                        !evaluation.error.isNullOrBlank())
                    println("Stage15 quality unsupported for ${resolution.name}: ${evaluation.error}")
                    continue
                }
                val plan = requireNotNull(evaluation.plan)
                output = VideoExporter(context).export(
                    timeline = timeline, onProgress = {}, onFinalizing = {},
                    exportMode = ExportMode.ALLOW_REENCODE, outputQuality = settings,
                )
                val resultUri = requireNotNull(output)
                MediaReader.validateExport(context, resultUri, info.durationMs, info.hasAudio)
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, resultUri)
                    val width = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                    val height = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                    assertTrue("Unexpected output short side: ${width}x${height}",
                        kotlin.math.abs(minOf(width, height) - plan.shortSide) <= 16)
                } finally {
                    retriever.release()
                }
                assertTrue("Source MP4 should be untouched", source.isFile && source.length() > 0L)
            } finally {
                output?.let { context.contentResolver.delete(it, null, null) }
                source.delete()
            }
        }
    }

    @Test fun unsupportedUHDIsNotSilentlyConvertedToLowerResolution() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "stage15_4k_guard_source.mp4")
        instrumentation.context.assets.open("quickcut_keyframes.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        try {
            val uri = Uri.fromFile(source)
            val meta = MediaReader.metadata(context, uri)
            val timeline = EditTimeline(listOf(
                VideoClip(1, uri.toString(), meta.durationMs, hasAudio = meta.hasAudio)
            ))
            val result = ExportQualityInspector.inspect(context, timeline,
                ExportQualitySettings(resolution = ExportResolution.ULTRA_HD_2160))
            assertTrue(result.error != null || result.plan?.shortSide == 2160)
        } finally {
            source.delete()
        }
    }
}
