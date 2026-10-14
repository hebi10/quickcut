package com.hebi.quickcut.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.batch.BatchClipMode
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.EditorSessionSnapshot
import com.hebi.quickcut.editor.EditorSessionStore
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.SaveDateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPersistenceInstrumentedTest {
    @Test
    fun lastUsedQuickBatchAndDateSettingsSurviveNewStoreInstances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = QuickCutSettings(context)
        val originalQuick = store.loadQuick()
        val originalBatch = store.loadBatch()
        val originalDate = store.loadSaveDate()
        try {
            store.saveQuick(QuickTrimSettings(4_000L, 500L, true))
            store.saveBatch(
                BatchDraftSettings(
                    name = "앞부분만 자르기",
                    mode = BatchClipMode.REMOVE_EDGES,
                    leadingMs = 4_000L,
                    trailingMs = 500L,
                    muteAudio = true,
                    selectedTemplateId = "saved-template",
                )
            )
            store.saveSaveDate(SaveDateMode.ORIGINAL)

            val restored = QuickCutSettings(context)
            assertEquals(QuickTrimSettings(4_000L, 500L, true), restored.loadQuick())
            assertEquals(4_000L, restored.loadBatch().leadingMs)
            assertEquals("saved-template", restored.loadBatch().selectedTemplateId)
            assertEquals(SaveDateMode.ORIGINAL, restored.loadSaveDate())
            assertFalse(
                context.getSharedPreferences("quickcut_last_settings_v1", 0)
                    .contains("original_action"),
            )
        } finally {
            store.saveQuick(originalQuick)
            store.saveBatch(originalBatch)
            store.saveSaveDate(originalDate)
        }
    }

    @Test
    fun editSessionCanBeRestoredAndDeliberatelyCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EditorSessionStore(context)
        val original = store.load()
        try {
            val clip = VideoClip(
                id = 17L, uri = "content://media/test/clip.mp4",
                sourceDurationMs = 50_000L, startMs = 4_000L,
                endMs = 48_000L, muted = true,
            )
            val saved = EditorSessionSnapshot(
                EditTimeline(listOf(clip)), 17L, 1_500L, true,
            )
            store.save(saved)
            val restored = EditorSessionStore(context).load()
            assertEquals(saved, restored)
            store.clear()
            assertNull(EditorSessionStore(context).load())
        } finally {
            if (original == null) store.clear() else store.save(original)
        }
    }
}
