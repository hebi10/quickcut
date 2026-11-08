package com.hebi.quickcut.media

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.CropGeometry
import com.hebi.quickcut.editor.CropRatio
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage16CropExportInstrumentedTest {
    @Test fun croppedVideoIsEncodedAtSquareAspectAndOriginalRemains() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = File(context.cacheDir, "stage16_crop_fixture.mp4")
        instrumentation.context.assets.open("quickcut_keyframes.mp4").use { input ->
            original.outputStream().use { output -> input.copyTo(output) }
        }
        var result: Uri? = null
        try {
            val sourceUri = Uri.fromFile(original)
            val meta = MediaReader.metadata(context, sourceUri)
            val crop = CropGeometry.centerForRatio(320, 240, CropRatio.SQUARE)
            val timeline = EditTimeline(listOf(VideoClip(
                id = 1, uri = sourceUri.toString(), sourceDurationMs = meta.durationMs,
                startMs = 0, endMs = minOf(meta.durationMs, 3_000),
                hasAudio = meta.hasAudio, crop = crop,
            )))
            val assessment = ExportPlanner.analyze(context, timeline, SaveDateMode.CURRENT)
            assertFalse(assessment.fastAvailable)
            assertTrue(assessment.explanation.contains("크롭"))
            assertTrue(runCatching {
                VideoExporter(context).export(timeline, {}, {}, exportMode = ExportMode.FAST_ONLY)
            }.exceptionOrNull() is ReencodeRequiredException)
            result = VideoExporter(context).export(
                timeline, onProgress = {}, onFinalizing = {}, exportMode = ExportMode.ALLOW_REENCODE,
            )
            MediaReader.validateExport(context, requireNotNull(result), timeline.totalDurationMs, meta.hasAudio)
            val retriever = MediaMetadataRetriever()
            val width: Int
            val height: Int
            try {
                retriever.setDataSource(context, requireNotNull(result))
                width = requireNotNull(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).toInt()
                height = requireNotNull(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)).toInt()
            } finally {
                retriever.release()
            }
            assertTrue("Square crop output must be square: ${width}x${height}",
                kotlin.math.abs(width - height) <= 4)
            assertTrue(original.length() > 0)
            assertEquals(1, timeline.clips.size)
        } finally {
            result?.let { context.contentResolver.delete(it, null, null) }
            original.delete()
        }
    }

    @Test fun cropPersistsAcrossEditSessionAndNamedProjectStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = com.hebi.quickcut.editor.EditorSessionStore(context, "quickcut_stage16_test_only")
        val crop = CropGeometry.centerForRatio(1920, 1080, CropRatio.VERTICAL)
        val timeline = EditTimeline(listOf(VideoClip(3L, "content://example/video", 25_000, crop = crop)))
        val snapshot = com.hebi.quickcut.editor.EditorSessionSnapshot(timeline, 3L, 0L, false)
        try {
            store.save(snapshot)
            assertEquals(snapshot, store.load())
            val changed = snapshot.copy(timeline = timeline.updateCrop(3L, null))
            store.save(changed)
            assertEquals(changed, store.load())
        } finally {
            store.clear()
        }
    }
}
