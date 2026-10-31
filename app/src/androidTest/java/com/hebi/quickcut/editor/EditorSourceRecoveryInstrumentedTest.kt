package com.hebi.quickcut.editor

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.os.ParcelFileDescriptor
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.media.MediaReader
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorSourceRecoveryInstrumentedTest {
    @Test fun inaccessibleRestoreAndInvalidReconnectPreserveSnapshotThenValidReconnectKeepsEdits() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EditorSessionStore(context)
        val previous = store.load()
        val exportStore = SingleExportStore(context)
        val previousExport = exportStore.load()
        val file = File.createTempFile("editor_reconnect", ".mp4", context.cacheDir)
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_keyframes.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val metadata = MediaReader.metadata(context, Uri.fromFile(file))
        val clip = VideoClip(17L, "content://unavailable/saved", metadata.durationMs,
            muted = true, hasAudio = metadata.hasAudio).withRangeUs(123_456L, 4_567_890L)
        val snapshot = EditorSessionSnapshot(EditTimeline(listOf(clip)), 17L, 321L, true)
        lateinit var model: EditorViewModel
        var modelInitialized = false
        try {
            exportStore.clear()
            store.save(snapshot)
            withContext(Dispatchers.Main) {
                model = EditorViewModel(context.applicationContext as Application)
                modelInitialized = true
                model.restoreSavedSession(context)
            }
            awaitLoad(model)
            assertEquals(snapshot, store.load())
            assertTrue(model.hasRestorableSession)
            assertTrue(model.hasSourceReconnect)
            assertNotNull(model.restoreRecoveryNotice)
            assertNull(model.errorMessage)
            withContext(Dispatchers.Main) { model.reconnectSavedSource(context, Uri.parse("content://unavailable/replacement")) }
            awaitLoad(model)
            assertEquals(snapshot, store.load())
            withContext(Dispatchers.Main) { model.reconnectSavedSource(context, Uri.fromFile(file)) }
            awaitLoad(model)
            assertEquals(QuickCutPage.EDITOR, model.page)
            assertEquals(snapshot.timeline.clips.map { it.copy(uri = Uri.fromFile(file).toString()) }, model.timeline.clips)
            assertEquals(snapshot.selectedId, model.selectedId)
            assertEquals(snapshot.playPositionMs, model.playPositionMs)
            assertTrue(model.quickMode)
            assertNull(model.restoreRecoveryNotice)
            assertFalse(model.hasSourceReconnect)
            assertEquals(snapshot.copy(timeline = model.timeline), store.load())
        } finally {
            if (modelInitialized) withContext(Dispatchers.Main) {
                model.cancelImport()
                ViewModelStore().apply { put("editor", model) }.clear()
            }
            previous?.let(store::save) ?: store.clear()
            previousExport?.let(exportStore::save) ?: exportStore.clear()
            file.delete()
        }
    }

    @Test fun cancellingBlockedProviderRetainsExistingEditAndLateResultCannotOverwriteNewImport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EditorSessionStore(context)
        val previous = store.load()
        val exportStore = SingleExportStore(context)
        val previousExport = exportStore.load()
        val file = File.createTempFile("editor_cancel", ".mp4", context.cacheDir)
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_keyframes.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val provider = BlockingSourceProvider(file)
        provider.attachInfo(context, ProviderInfo().apply { authority = "quickcut.cancel.fixture" })
        val resolver = ContentResolver.wrap(provider)
        val blockedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
        }
        lateinit var model: EditorViewModel
        var modelInitialized = false
        try {
            exportStore.clear()
            withContext(Dispatchers.Main) {
                model = EditorViewModel(context.applicationContext as Application)
                modelInitialized = true
                model.importVideos(context, listOf(Uri.fromFile(file)))
            }
            awaitLoad(model)
            withContext(Dispatchers.Main) { model.trimSelected(1_000L, 4_000L); model.saveSessionOnStop() }
            val saved = store.load()
            val timeline = model.timeline
            withContext(Dispatchers.Main) {
                model.importVideos(blockedContext, listOf(Uri.parse("content://quickcut.cancel.fixture/old")), append = true)
            }
            assertTrue("Provider must already be blocked before cancellation", provider.entered.await(10L, TimeUnit.SECONDS))
            withContext(Dispatchers.Main) {
                model.cancelImport()
                assertEquals(QuickCutPage.EDITOR, model.page)
                assertEquals(timeline, model.timeline)
                assertEquals(saved, store.load())
                model.importVideos(context, listOf(Uri.fromFile(file)))
            }
            awaitLoad(model)
            val newerTimeline = model.timeline
            provider.release.countDown()
            assertTrue(provider.returned.await(10L, TimeUnit.SECONDS))
            // Give the old retriever time to finish its native read and dispatch its cancelled result.
            delay(500L)
            assertEquals(QuickCutPage.EDITOR, model.page)
            assertEquals(newerTimeline, model.timeline)
            assertEquals(Uri.fromFile(file).toString(), model.timeline.clips.single().uri)
            assertEquals(0L, model.timeline.clips.single().startMs)
            assertNull(model.errorMessage)
        } finally {
            provider.release.countDown()
            if (modelInitialized) withContext(Dispatchers.Main) {
                model.cancelImport()
                ViewModelStore().apply { put("editor", model) }.clear()
            }
            previous?.let(store::save) ?: store.clear()
            previousExport?.let(exportStore::save) ?: exportStore.clear()
            file.delete()
        }
    }


    private class BlockingSourceProvider(private val source: File) : ContentProvider() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/mp4"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            entered.countDown()
            check(release.await(25L, TimeUnit.SECONDS)) { "Fixture provider was not released" }
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).also { returned.countDown() }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private suspend fun awaitLoad(model: EditorViewModel) = withTimeout(25_000L) {
        while (withContext(Dispatchers.Main) { model.page == QuickCutPage.IMPORTING }) delay(50L)
    }
}
