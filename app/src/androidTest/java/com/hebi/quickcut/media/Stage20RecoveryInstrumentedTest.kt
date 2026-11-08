package com.hebi.quickcut.media

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.AudioAdjustment
import com.hebi.quickcut.editor.CropGeometry
import com.hebi.quickcut.editor.CropRatio
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.EditorSessionSnapshot
import com.hebi.quickcut.editor.EditorSessionStore
import com.hebi.quickcut.editor.TextLayer
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.segments.ExtractionItem
import com.hebi.quickcut.segments.ExtractionRange
import com.hebi.quickcut.segments.ExtractionSession
import com.hebi.quickcut.segments.ExtractionSessionStore
import com.hebi.quickcut.segments.ExtractionStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated app-private fixtures: never touch the user's projects or gallery. */
@RunWith(AndroidJUnit4::class)
class Stage20RecoveryInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun legacySchemaTwoLoadsWithSafeDefaultsThenRoundTripsNewFields() {
        val name = "quickcut_stage20_legacy_test_only"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val original = JSONObject().apply {
                put("schemaVersion", 2)
                put("clips", JSONArray().put(JSONObject().apply {
                    put("id", 7)
                    put("uri", "content://quickcut-test/legacy")
                    put("duration", 3_000)
                    put("start", 0)
                    put("end", 3_000)
                    put("hasAudio", true)
                    put("muted", false)
                }))
                put("selectedId", 7)
                put("positionMs", 750)
                put("quickMode", false)
            }
            assertTrue(prefs.edit().putString("session", original.toString()).commit())
            val store = EditorSessionStore(context, name)
            val restored = requireNotNull(store.load())
            val clip = restored.timeline.clips.single()
            assertNull(clip.crop)
            assertTrue(clip.audio.isDefault)
            assertTrue(clip.textLayers.isEmpty())
            assertEquals(750L, restored.playPositionMs)

            val adjusted = restored.copy(timeline = restored.timeline
                .updateCrop(7, CropGeometry.centerForRatio(1280, 720, CropRatio.SQUARE))
                .updateAudio(7, AudioAdjustment(65, 250, 300))
                .updateTextLayers(7, listOf(TextLayer(1, "새 문구", 500, 2_000))))
            store.save(adjusted)
            assertEquals(adjusted, store.load())
            assertEquals(5, JSONObject(requireNotNull(prefs.getString("session", null)))
                .getInt("schemaVersion"))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun savedMuteCropAudioAndTextCanBeUndoneAfterRecovery() {
        val store = EditorSessionStore(context, "quickcut_stage20_combined_test_only")
        store.clear()
        try {
            val original = EditTimeline(listOf(VideoClip(
                1, "content://quickcut-test/new", 3_000,
            )))
            val combined = original
                .updateCrop(1, CropGeometry.centerForRatio(1920, 1080, CropRatio.VERTICAL))
                .updateAudio(1, AudioAdjustment(50, 350, 400))
                .updateTextLayers(1, listOf(TextLayer(1, "자막", 100, 2_950)))
                .toggleMute(1)
            val snapshot = EditorSessionSnapshot(combined, 1, 2_000, false)
            store.save(snapshot)
            assertEquals(snapshot, store.load())
            val edited = requireNotNull(store.load()).timeline.updateRange(1, 1_000, 3_000)
            assertTrue(edited.clips.single().muted)
            assertEquals(50, edited.clips.single().audio.volumePercent)
            assertEquals(0L, edited.clips.single().textLayers.single().startMs)
            assertEquals(1_950L, edited.clips.single().textLayers.single().endMs)
        } finally {
            store.clear()
        }
    }

    @Test fun extractionJournalKeepsCompletedAndInterruptedSegmentsSeparate() {
        val store = ExtractionSessionStore(context, "quickcut_stage20_extraction_test_only.json")
        store.clear()
        try {
            val rows = listOf(
                ExtractionItem(
                    range = ExtractionRange(1, 0, 1_000),
                    status = ExtractionStatus.SAVED,
                    outputUri = "content://media/external/video/media/verified",
                ),
                ExtractionItem(
                    range = ExtractionRange(2, 1_000, 2_000),
                    status = ExtractionStatus.SAVING,
                ),
            )
            val session = ExtractionSession(
                sourceUri = "content://quickcut-test/source",
                sourceName = "sample.mp4",
                durationMs = 3_000,
                hasAudio = true,
                items = rows,
            )
            store.save(session)
            val recovered = requireNotNull(store.load())
            assertEquals(session, recovered)
            assertEquals(ExtractionStatus.SAVED, recovered.items.first().status)
            assertEquals(ExtractionStatus.SAVING, recovered.items.last().status)
            assertNull(recovered.items.last().outputUri)
        } finally {
            store.clear()
        }
    }

    @Test fun effectsCannotBypassUserReencodeConsent() = runBlocking {
        val crop = CropGeometry.centerForRatio(1280, 720, CropRatio.SQUARE)
        val baseline = VideoClip(
            1, "content://quickcut-test/not-opened", 3_000, hasAudio = true,
        )
        val clips = listOf(
            baseline.copy(crop = crop),
            baseline.copy(audio = AudioAdjustment(40, 500, 500)),
            baseline.copy(textLayers = listOf(TextLayer(1, "Text", 500, 2_000))),
            baseline.copy(
                crop = crop,
                audio = AudioAdjustment(40, 500, 500),
                textLayers = listOf(TextLayer(1, "Text", 500, 2_000)),
            ),
        )
        clips.forEach { clip ->
            val failure = runCatching {
                VideoExporter(context).export(
                    timeline = EditTimeline(listOf(clip)),
                    onProgress = {},
                    onFinalizing = {},
                    exportMode = ExportMode.FAST_ONLY,
                )
            }.exceptionOrNull()
            assertTrue("Effect was not blocked before decoding: ${failure?.message}",
                failure is ReencodeRequiredException)
        }
    }
}
