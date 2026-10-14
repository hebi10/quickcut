package com.hebi.quickcut.batch

import android.app.Application
import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.SaveDateMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchRecoveryInstrumentedTest {
    @Test
    fun recreatingBatchRestoresSavedOutputAndDoesNotRestoreDeletionConsent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val input = File(context.cacheDir, "quickcut_recovery_fixture.mp4")
        BatchSessionStore(context).clear()
        val first = withContext(Dispatchers.Main) { BatchViewModel(application) }
        withContext(Dispatchers.Main) { first.resetBatch() }
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_keyframes.mp4")
            .use { source -> input.outputStream().use { target -> source.copyTo(target) } }
        try {
            withContext(Dispatchers.Main) {
                first.draftName = "복구 검사"
                first.draftMode = BatchClipMode.KEEP_FIRST
                first.draftLeadingSeconds = "2"
                first.saveDateMode = SaveDateMode.CURRENT
                first.originalAction = OriginalAction.DELETE
                first.addVideos(context, listOf(Uri.fromFile(input)))
            }
            withTimeout(30_000L) { while (first.isImporting) delay(50L) }
            withContext(Dispatchers.Main) { first.start(context, exportMode = ExportMode.FAST_ONLY) }
            withTimeout(60_000L) {
                while (first.runState != BatchRunState.FINISHED) delay(50L)
            }
            assertEquals(1, first.successes)
            val saved = first.files.single().savedOutputUri
            assertNotNull(saved)
            val recreated = withContext(Dispatchers.Main) { BatchViewModel(application) }
            assertEquals("저장 완료 파일을 재실행 시 복구해야 합니다", 1, recreated.files.size)
            assertEquals(BatchFileStatus.SAVED, recreated.files.single().status)
            assertEquals(saved, recreated.files.single().savedOutputUri)
            assertEquals(OriginalAction.KEEP, recreated.originalAction)
            assertEquals(OriginalAction.KEEP, recreated.performedOriginalAction)
            withTimeout(30_000L) { while (recreated.isRecovering) delay(50L) }
            withContext(Dispatchers.Main) { recreated.start(context, exportMode = ExportMode.FAST_ONLY) }
            withTimeout(60_000L) {
                while (recreated.runState != BatchRunState.FINISHED) delay(50L)
            }
            assertEquals("완료한 영상은 중복 저장하면 안 됩니다", saved, recreated.files.single().savedOutputUri)
            assertTrue(input.exists())
            withContext(Dispatchers.Main) { recreated.resetBatch() }
        } finally {
            withContext(Dispatchers.Main) { first.cancel() }
            first.files.mapNotNull { it.savedOutputUri }.forEach {
                runCatching { context.contentResolver.delete(Uri.parse(it), null, null) }
            }
            withContext(Dispatchers.Main) { first.resetBatch() }
            input.delete()
        }
    }

    @Test
    fun exportServiceIsPrivateAndDeclaresMediaProcessingForBackgroundWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = runCatching {
            context.packageManager.getServiceInfo(
                ComponentName(context.packageName, "com.hebi.quickcut.export.ExportWorkService"), 0,
            )
        }.getOrNull()
        assertNotNull("백그라운드 영상 처리 서비스가 필요합니다", service)
        assertFalse(service!!.exported)
        assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING != 0)
    }
}
