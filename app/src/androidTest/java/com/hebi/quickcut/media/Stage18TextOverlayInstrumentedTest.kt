package com.hebi.quickcut.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.EditorSessionSnapshot
import com.hebi.quickcut.editor.EditorSessionStore
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
class Stage18TextOverlayInstrumentedTest {
    @Test fun textAppearsOnlyBetweenChosenTimesAndOriginalVideoIsPreserved() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "stage18_text_source.mp4")
        instrumentation.context.assets.open("quickcut_fixture.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        var published: Uri? = null
        try {
            val originalBytes = source.readBytes()
            val uri = Uri.fromFile(source)
            val info = MediaReader.metadata(context, uri)
            assertTrue(info.durationMs >= 2_500L)
            val title = TextLayer(
                id = 1, text = "STAGE18", startMs = 700L, endMs = 1_850L,
                x = 0.5f, y = 0.5f, size = 150, color = TextLayerColor.YELLOW,
            )
            val timeline = EditTimeline(listOf(
                VideoClip(1, uri.toString(), info.durationMs, hasAudio = info.hasAudio,
                    textLayers = listOf(title))
            ))
            val assessment = ExportPlanner.analyze(context, timeline, SaveDateMode.CURRENT)
            assertTrue(!assessment.fastAvailable)
            assertTrue(assessment.explanation.contains("텍스트"))
            assertTrue(runCatching {
                VideoExporter(context).export(timeline, {}, {}, exportMode = ExportMode.FAST_ONLY)
            }.exceptionOrNull() is ReencodeRequiredException)

            published = VideoExporter(context).export(
                timeline, onProgress = {}, onFinalizing = {},
                exportMode = ExportMode.ALLOW_REENCODE,
            )
            MediaReader.validateExport(context, requireNotNull(published), info.durationMs, info.hasAudio)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, requireNotNull(published))
                val before = requireNotNull(retriever.getScaledFrameAtTime(
                    250_000L, MediaMetadataRetriever.OPTION_CLOSEST, 320, 240))
                val during = requireNotNull(retriever.getScaledFrameAtTime(
                    1_200_000L, MediaMetadataRetriever.OPTION_CLOSEST, 320, 240))
                val after = requireNotNull(retriever.getScaledFrameAtTime(
                    2_400_000L, MediaMetadataRetriever.OPTION_CLOSEST, 320, 240))
                try {
                    val early = countYellowPixels(before)
                    val visible = countYellowPixels(during)
                    val late = countYellowPixels(after)
                    assertTrue("Timed overlay not visible: $early/$visible/$late",
                        visible > early + 18 && visible > late + 18)
                } finally {
                    before.recycle(); during.recycle(); after.recycle()
                }
            } finally {
                retriever.release()
            }
            assertTrue(source.readBytes().contentEquals(originalBytes))
        } finally {
            published?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }

    @Test fun sessionAndProjectStorageRoundTripKoreanTextAndTiming() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EditorSessionStore(context, "quickcut_stage18_test_only")
        val title = TextLayer(1L, "한글 문구\n두 번째 줄", 500L, 2_500L)
        val second = TextLayer(2L, "QuickCut", 1_000L, 2_900L, x = 0.7f,
            y = 0.7f, size = 120, darkBackground = true)
        val snapshot = EditorSessionSnapshot(
            timeline = EditTimeline(listOf(VideoClip(1L, "content://test/source", 3_000L,
                textLayers = listOf(title, second)))),
            selectedId = 1L, playPositionMs = 1_500L, quickMode = false,
        )
        try {
            store.save(snapshot)
            assertEquals(snapshot, store.load())
            val cleared = snapshot.copy(timeline = EditTimeline(listOf(
                snapshot.timeline.clips.single().copy(textLayers = emptyList())
            )))
            store.save(cleared)
            assertEquals(cleared, store.load())
        } finally { store.clear() }
    }

    private fun countYellowPixels(image: Bitmap): Int {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return pixels.count {
            android.graphics.Color.red(it) > 190 &&
                android.graphics.Color.green(it) > 160 &&
                android.graphics.Color.blue(it) < 180
        }
    }
}
