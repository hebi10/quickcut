package com.hebi.quickcut.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.hebi.quickcut.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ExportWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var workId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var observation: Job? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "영상 저장 진행", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(WORK_ID)
        if (intent?.action == CANCEL) {
            ExportWorkMonitor.cancel(id)
            return START_NOT_STICKY
        }
        val state = ExportWorkMonitor.status.value
        if (id == null || state?.id != id) {
            if (workId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        workId = id
        try {
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIFICATION_ID, notification(state), type)
            if (wakeLock?.isHeld != true) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuickCut:VideoExport").apply {
                        setReferenceCounted(false)
                        acquire(6L * 60L * 60L * 1_000L)
                    }
            }
            ExportWorkMonitor.serviceReady(id)
            observation?.cancel()
            observation = scope.launch {
                ExportWorkMonitor.status.collectLatest { latest ->
                    if (latest == null) {
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification(latest))
                    }
                }
            }
        } catch (error: Exception) {
            ExportWorkMonitor.serviceFailed(id, error)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ExportWorkMonitor.terminate(workId)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ExportWorkMonitor.terminate(workId)
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notification(state: ExportWorkStatus): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(state.title)
            .setContentText(state.detail)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, state.progress ?: 0, state.progress == null)
        if (state.canCancel) {
            val stop = PendingIntent.getService(this, 1, Intent(this, ExportWorkService::class.java)
                .setAction(CANCEL).putExtra(WORK_ID, state.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "저장 중단", stop).build())
        }
        return builder.build()
    }

    companion object {
        const val WORK_ID = "work_id"
        private const val CHANNEL_ID = "quickcut_export"
        private const val NOTIFICATION_ID = 1001
        private const val CANCEL = "com.hebi.quickcut.CANCEL_EXPORT"
    }
}
