package com.hebi.quickcut.export

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import com.hebi.quickcut.media.ExportStagingFiles

data class ExportWorkStatus(
    val id: String,
    val title: String,
    val detail: String = "영상 처리 준비 중",
    val progress: Int? = null,
    val canCancel: Boolean = true,
)

object ExportWorkMonitor {
    private val current = MutableStateFlow<ExportWorkStatus?>(null)
    val status = current.asStateFlow()
    var isRunning by mutableStateOf(false)
        private set
    private var cancellation: (() -> Unit)? = null
    private var termination: (() -> Unit)? = null
    private var terminationRequested = false
    private var ready: CompletableDeferred<Unit>? = null

    fun begin(context: Context, title: String, onCancel: () -> Unit): String =
        begin(context, title, onCancel, onCancel)

    fun begin(context: Context, title: String, onCancel: () -> Unit, onTerminate: () -> Unit): String {
        check(!isRunning) { "다른 영상 저장이 진행 중입니다. 완료 후 다시 시작해 주세요." }
        ExportStagingFiles.cleanup(context.applicationContext.cacheDir)
        val id = UUID.randomUUID().toString()
        cancellation = onCancel
        termination = onTerminate
        terminationRequested = false
        ready = CompletableDeferred()
        current.value = ExportWorkStatus(id, title)
        isRunning = true
        try {
            requestNotification(context)
            context.applicationContext.startForegroundService(
                Intent(context, ExportWorkService::class.java).putExtra(ExportWorkService.WORK_ID, id),
            )
        } catch (error: Exception) {
            finish(id)
            throw IllegalStateException("백그라운드 저장을 시작하지 못했습니다. 앱을 열고 다시 시도해 주세요.", error)
        }
        return id
    }

    suspend fun awaitReady(id: String) {
        check(current.value?.id == id) { "저장 작업이 종료됐습니다." }
        withTimeout(10_000L) { ready?.await() ?: error("저장 서비스가 종료됐습니다.") }
    }

    fun serviceReady(id: String) {
        if (current.value?.id == id) ready?.complete(Unit)
    }

    fun serviceFailed(id: String, error: Exception) {
        if (current.value?.id == id) ready?.completeExceptionally(error)
    }

    fun update(id: String, detail: String, progress: Int? = null, canCancel: Boolean = true) {
        val old = current.value ?: return
        if (old.id == id) current.value = old.copy(detail = detail, progress = progress, canCancel = canCancel)
    }

    fun cancel(id: String?) {
        if (id != null && current.value?.id == id && current.value?.canCancel == true) cancellation?.invoke()
    }

    fun terminate(id: String?) {
        if (id != null && current.value?.id == id && !terminationRequested) {
            terminationRequested = true
            termination?.invoke()
        }
    }

    fun finish(id: String) {
        if (current.value?.id != id) return
        ready?.cancel()
        ready = null
        cancellation = null
        termination = null
        current.value = null
        isRunning = false
    }

    private fun requestNotification(context: Context) {
        if (Build.VERSION.SDK_INT < 33 || context !is Activity ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) return
        val preferences = context.getSharedPreferences("quickcut_notification", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("requested", false)) {
            preferences.edit().putBoolean("requested", true).apply()
            context.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 110)
        }
    }
}
