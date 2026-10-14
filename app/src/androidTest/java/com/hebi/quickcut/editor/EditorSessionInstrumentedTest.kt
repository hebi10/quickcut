package com.hebi.quickcut.editor

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorSessionInstrumentedTest {
    @Test
    fun reopeningEditorRestoresTrimAndQuickModeForReadableVideo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as Application
        val file = File(context.cacheDir, "quickcut_session_fixture.mp4")
        val store = EditorSessionStore(context)
        val previous = store.load()
        val source = InstrumentationRegistry.getInstrumentation().context.assets
            .open("quickcut_keyframes.mp4")
        source.use { it.copyTo(file.outputStream()) }
        val first = EditorViewModel(app)
        try {
            withContext(Dispatchers.Main) {
                first.changeQuickMode(true)
                first.importVideos(context, listOf(Uri.fromFile(file)))
            }
            withTimeout(25_000L) {
                while (first.page != QuickCutPage.EDITOR) delay(100L)
            }
            val original = first.timeline.clips.single()
            assertTrue(original.sourceDurationMs >= 7_000L)
            withContext(Dispatchers.Main) {
                first.trimSelected(4_000L, 7_000L)
                first.saveSessionOnStop()
            }

            val restarted = EditorViewModel(app)
            assertTrue(restarted.hasRestorableSession)
            withContext(Dispatchers.Main) {
                restarted.restoreSavedSession(context)
            }
            withTimeout(25_000L) {
                while (restarted.page == QuickCutPage.IMPORTING) delay(100L)
            }
            assertEquals(QuickCutPage.EDITOR, restarted.page)
            assertEquals(4_000L, restarted.timeline.clips.single().startMs)
            assertEquals(7_000L, restarted.timeline.clips.single().endMs)
            assertTrue(restarted.quickMode)
        } finally {
            if (previous == null) store.clear() else store.save(previous)
            file.delete()
        }
    }
}
