package com.hebi.quickcut.batch

import android.app.ActivityManager
import android.app.NotificationManager
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Build
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.hebi.quickcut.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportServiceInstrumentedTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

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

    @Test fun systemTerminationStopsTheJobEvenWhenUserCancellationIsBlocked() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var userCancelled = false
        var terminated = false
        val id = withContext(Dispatchers.Main) {
            ExportWorkMonitor.begin(context, "시스템 중단 검사", { userCancelled = true }, { terminated = true })
        }
        try {
            withContext(Dispatchers.Main) {
                ExportWorkMonitor.awaitReady(id)
                ExportWorkMonitor.update(id, "파일 등록 중", canCancel = false)
                ExportWorkMonitor.cancel(id)
                ExportWorkMonitor.terminate(id)
            }
            assertFalse(userCancelled)
            assertTrue(terminated)
        } finally { withContext(Dispatchers.Main) { ExportWorkMonitor.finish(id) } }
        withTimeout(10_000L) { while (serviceRunning(context)) delay(50L) }
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

    @Test fun notificationCancelCommandDoesNotLeaveTheCompletedServiceRunning() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        var cancelled = false
        val id = withContext(Dispatchers.Main) {
            ExportWorkMonitor.begin(context, "알림 중단 검사") { cancelled = true }
        }
        try {
            withContext(Dispatchers.Main) { ExportWorkMonitor.awaitReady(id) }
            val notifications = context.getSystemService(NotificationManager::class.java)
            val stop = withTimeout(10_000L) {
                var action = notifications.activeNotifications.firstOrNull {
                    it.notification.actions?.isNotEmpty() == true
                }?.notification?.actions?.firstOrNull()?.actionIntent
                while (action == null) {
                    delay(50L)
                    action = notifications.activeNotifications.firstOrNull {
                        it.notification.actions?.isNotEmpty() == true
                    }?.notification?.actions?.firstOrNull()?.actionIntent
                }
                checkNotNull(action)
            }
            // Exercise Android's real service startId increment for the action.
            // Calling ExportWorkMonitor.cancel directly does not reproduce it.
            stop.send()
            withTimeout(10_000L) { while (!cancelled) delay(25L) }
            withContext(Dispatchers.Main) { ExportWorkMonitor.finish(id) }
            assertServiceStops(context,
                "The notification CANCEL startId must not keep a completed service alive")
        } finally {
            withContext(Dispatchers.Main) { ExportWorkMonitor.finish(id) }
            stopServiceAfterTest(context)
        }
    }

    @Test fun staleCancelCommandCannotStopNextWorkOrKeepItsFinishedServiceAlive() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = withContext(Dispatchers.Main) {
            ExportWorkMonitor.begin(context, "이전 작업") {}
        }
        withContext(Dispatchers.Main) { ExportWorkMonitor.awaitReady(first) }
        var nextCancelled = false
        val next = withContext(Dispatchers.Main) {
            ExportWorkMonitor.finish(first)
            ExportWorkMonitor.begin(context, "새 작업") { nextCancelled = true }
        }
        try {
            withContext(Dispatchers.Main) {
                ExportWorkMonitor.awaitReady(next)
                // Explicit stale extras model an already queued command. Reusing
                // PendingIntent would update its extras to the next work ID.
                context.startService(Intent(context, ExportWorkService::class.java)
                    .setAction("com.hebi.quickcut.CANCEL_EXPORT")
                    .putExtra(ExportWorkService.WORK_ID, first))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            delay(250L) // Let the asynchronous service command reach onStartCommand.
            assertFalse("A prior notification must not cancel the following export", nextCancelled)
            assertEquals(next, ExportWorkMonitor.status.value?.id)
            assertTrue(serviceRunning(context))
            withContext(Dispatchers.Main) { ExportWorkMonitor.finish(next) }
            assertServiceStops(context,
                "A stale CANCEL startId must not keep the following completed service alive")
        } finally {
            withContext(Dispatchers.Main) { ExportWorkMonitor.finish(next) }
            stopServiceAfterTest(context)
        }
    }

    @Test fun lateCancelAfterCompletionDoesNotStartAnIdleServiceThatStaysAlive() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val finished = withContext(Dispatchers.Main) {
            ExportWorkMonitor.begin(context, "완료 뒤 알림 검사") {}
        }
        try {
            withContext(Dispatchers.Main) {
                ExportWorkMonitor.awaitReady(finished)
                ExportWorkMonitor.finish(finished)
            }
            assertServiceStops(context, "The original work should stop normally")
            withContext(Dispatchers.Main) {
                context.startService(Intent(context, ExportWorkService::class.java)
                    .setAction("com.hebi.quickcut.CANCEL_EXPORT")
                    .putExtra(ExportWorkService.WORK_ID, finished))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            delay(250L)
            assertServiceStops(context, "A late CANCEL must not leave an idle service running")
        } finally {
            withContext(Dispatchers.Main) { ExportWorkMonitor.finish(finished) }
            stopServiceAfterTest(context)
        }
    }

    private suspend fun assertServiceStops(context: Context, message: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (serviceRunning(context) && SystemClock.elapsedRealtime() < deadline) delay(50L)
        assertFalse(message, serviceRunning(context))
        assertFalse(ExportWorkMonitor.isRunning)
    }

    private suspend fun stopServiceAfterTest(context: Context) {
        context.stopService(Intent(context, ExportWorkService::class.java))
        withTimeout(10_000L) { while (serviceRunning(context)) delay(50L) }
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .any { it.service.className == ExportWorkService::class.java.name }
}

