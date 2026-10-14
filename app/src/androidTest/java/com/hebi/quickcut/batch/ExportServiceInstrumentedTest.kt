package com.hebi.quickcut.batch

import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.export.ExportWorkMonitor
import com.hebi.quickcut.export.ExportWorkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportServiceInstrumentedTest {
    @Test fun foregroundWorkSupportsCancellationAndStopsAfterCompletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var cancelled = false
        val id = withContext(Dispatchers.Main) {
            ExportWorkMonitor.begin(context, "서비스 검사") { cancelled = true }
        }
        try {
            withContext(Dispatchers.Main) { ExportWorkMonitor.awaitReady(id) }
            assertTrue(serviceRunning(context))
            withContext(Dispatchers.Main) {
                ExportWorkMonitor.update(id, "파일 등록 중", canCancel = false)
                ExportWorkMonitor.cancel(id)
            }
            assertFalse(cancelled)
            withContext(Dispatchers.Main) {
                ExportWorkMonitor.update(id, "영상 처리 중")
                ExportWorkMonitor.cancel(id)
            }
            assertTrue(cancelled)
        } finally {
            withContext(Dispatchers.Main) { ExportWorkMonitor.finish(id) }
        }
        withTimeout(10_000L) { while (serviceRunning(context)) delay(50L) }
        assertFalse(ExportWorkMonitor.isRunning)
    }

    @Test fun finishingAStaleLeaseDoesNotCancelTheFollowingWork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = withContext(Dispatchers.Main) { ExportWorkMonitor.begin(context, "첫 작업") {} }
        withContext(Dispatchers.Main) { ExportWorkMonitor.awaitReady(first) }
        var cancelled = false
        val next = withContext(Dispatchers.Main) {
            ExportWorkMonitor.finish(first)
            ExportWorkMonitor.begin(context, "다음 작업") { cancelled = true }
        }
        try {
            withContext(Dispatchers.Main) {
                ExportWorkMonitor.finish(first)
                ExportWorkMonitor.cancel(first)
                ExportWorkMonitor.awaitReady(next)
            }
            assertTrue(ExportWorkMonitor.isRunning)
            assertTrue(serviceRunning(context))
            assertFalse(cancelled)
        } finally { withContext(Dispatchers.Main) { ExportWorkMonitor.finish(next) } }
        withTimeout(10_000L) { while (serviceRunning(context)) delay(50L) }
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .any { it.service.className == ExportWorkService::class.java.name }
}
