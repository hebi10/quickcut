package com.hebi.quickcut.batch

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.OriginalActionStatus
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.OriginalMediaDates
import com.hebi.quickcut.media.OriginalTimestamp
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.media.VideoExporter
import com.hebi.quickcut.media.ExportPlanner
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.ReencodeRequiredException
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.settings.BatchDraftSettings
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

enum class BatchRunState { SETUP, RUNNING, FINISHED }
enum class BatchFileStatus { WAITING, RUNNING, SAVED, FAILED, SKIPPED }

data class BatchFile(
    val uri: String,
    val displayName: String,
    val durationMs: Long = 0L,
    val hasAudio: Boolean = false,
    val originalDate: OriginalTimestamp? = null,
    val status: BatchFileStatus = BatchFileStatus.WAITING,
    val reason: String? = null,
    val progress: Int? = null,
    val savedOutputUri: String? = null,
)

data class BatchExportOverview(
    val ready: Int,
    val fast: Int,
    val needsEncoding: Int,
    val skipped: Int,
)

class BatchViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = QuickCutSettings(application)
    private val lastDraft = settings.loadBatch()

    init {
        viewModelScope.launch {
            snapshotFlow {
                BatchDraftSettings(
                    name = draftName,
                    mode = draftMode,
                    leadingMs = parseMillis(draftLeadingSeconds) ?: lastDraft.leadingMs,
                    trailingMs = parseMillis(draftTrailingSeconds) ?: lastDraft.trailingMs,
                    muteAudio = draftMute,
                    selectedTemplateId = selectedTemplateId,
                )
            }.distinctUntilChanged().collect { settings.saveBatch(it) }
        }
        viewModelScope.launch {
            snapshotFlow { saveDateMode }.distinctUntilChanged().collect {
                settings.saveSaveDate(it)
            }
        }
    }

    suspend fun inspectExport(context: Context, retryOnly: Boolean): BatchExportOverview {
        val template = previewTemplate()
            ?: throw IllegalArgumentException("편집 설정을 확인해 주세요.")
        val dateMode = saveDateMode
        val targets = files.filter { it.durationMs >= 100L &&
            it.status != BatchFileStatus.SAVED &&
            (!retryOnly || it.status == BatchFileStatus.FAILED ||
                it.status == BatchFileStatus.SKIPPED)
        }
        var fast = 0
        var encoded = 0
        var skipped = 0
        for (file in targets) {
            if (dateMode == SaveDateMode.ORIGINAL && file.originalDate == null) {
                skipped++
                continue
            }
            when (val plan = template.plan(file.uri, file.durationMs, file.hasAudio)) {
                is BatchPlan.Skip -> skipped++
                is BatchPlan.Ready -> {
                    val assessment = ExportPlanner.analyze(context, plan.timeline, dateMode)
                    if (assessment.fastAvailable) fast++ else encoded++
                }
            }
        }
        return BatchExportOverview(
            ready = fast + encoded,
            fast = fast,
            needsEncoding = encoded,
            skipped = skipped,
        )
    }

    var templates by mutableStateOf<List<BatchTemplate>>(emptyList())
        private set
    var files by mutableStateOf<List<BatchFile>>(emptyList())
        private set
    var runState by mutableStateOf(BatchRunState.SETUP)
        private set
    var isImporting by mutableStateOf(false)
        private set
    var notification by mutableStateOf<String?>(null)
        private set
    var completedCount by mutableStateOf(0)
        private set
    var selectedTemplateId by mutableStateOf<String?>(lastDraft.selectedTemplateId)
        private set

    var draftName by mutableStateOf(lastDraft.name)
    var draftMode by mutableStateOf(lastDraft.mode)
    var draftLeadingSeconds by mutableStateOf(formatSeconds(lastDraft.leadingMs))
    var draftTrailingSeconds by mutableStateOf(formatSeconds(lastDraft.trailingMs))
    var draftMute by mutableStateOf(lastDraft.muteAudio)
    var saveDateMode by mutableStateOf(settings.loadSaveDate())
    var originalAction by mutableStateOf(OriginalAction.KEEP)
    var performedOriginalAction by mutableStateOf(OriginalAction.KEEP)
        private set
    var originalStatus by mutableStateOf(OriginalActionStatus.NONE)
        private set

    private var stored = false
    private var job: Job? = null
    // Only files exported successfully in the current run are eligible
    // for an original-media operation. Prior runs are never included.
    private var sourceUrisSavedThisRun by mutableStateOf<List<String>>(emptyList())

    val isRunning: Boolean get() = runState == BatchRunState.RUNNING
    val successes: Int get() = files.count { it.status == BatchFileStatus.SAVED }
    val failures: Int get() = files.count { it.status == BatchFileStatus.FAILED }
    val skips: Int get() = files.count { it.status == BatchFileStatus.SKIPPED }
    val originalCandidates: List<String>
        get() = sourceUrisSavedThisRun.distinct().filter { candidate ->
            files.any { it.uri == candidate && it.status == BatchFileStatus.SAVED }
        }
    val savedOutputForConfirmation: Uri?
        get() = files.firstOrNull {
            it.status == BatchFileStatus.SAVED && it.uri in originalCandidates
        }?.savedOutputUri?.let(Uri::parse)

    fun loadTemplates(context: Context) {
        if (stored) return
        stored = true
        templates = TemplateStore(context).load()
        if (selectedTemplateId != null && templates.none { it.id == selectedTemplateId }) {
            selectedTemplateId = null
        }
    }

    fun chooseTemplate(templateId: String?) {
        if (isRunning) return
        selectedTemplateId = templateId
        val selected = templates.firstOrNull { it.id == templateId } ?: return
        draftName = selected.name
        draftMode = selected.mode
        draftLeadingSeconds = formatSeconds(selected.leadingMs)
        draftTrailingSeconds = formatSeconds(selected.trailingMs)
        draftMute = selected.muteAudio
    }

    private fun parseMillis(text: String): Long? {
        val number = text.replace(',', '.').toDoubleOrNull()
        if (number == null || !number.isFinite() || number !in 0.0..7200.0) return null
        return (number * 1_000.0).toLong()
    }

    private fun currentDraft(): BatchTemplate? {
        // Disabled inputs must never block validation or alter the saved template.
        // Keep their text in the editor state so switching modes restores it.
        val head = if (draftMode.usesLeading) {
            parseMillis(draftLeadingSeconds) ?: return null
        } else 0L
        val tail = if (draftMode.usesTrailing) {
            parseMillis(draftTrailingSeconds) ?: return null
        } else 0L
        return BatchTemplate(
            id = selectedTemplateId ?: UUID.randomUUID().toString(),
            name = draftName.trim(),
            mode = draftMode,
            leadingMs = head,
            trailingMs = tail,
            muteAudio = draftMute,
        )
    }

    fun previewTemplate(): BatchTemplate? = currentDraft()?.takeIf { it.checkValidity() == null }

    fun saveTemplate(context: Context) {
        if (isRunning) return
        val proposed = currentDraft()
        if (proposed == null) {
            notification = "시간은 0~7,200초 사이의 숫자로 입력해 주세요."
            return
        }
        proposed.checkValidity()?.let {
            notification = it
            return
        }
        try {
            templates = TemplateStore(context).save(proposed)
            selectedTemplateId = proposed.id
            notification = "템플릿을 저장했습니다."
        } catch (e: Exception) {
            notification = e.localizedMessage ?: "템플릿을 저장하지 못했습니다."
        }
    }

    fun removeTemplate(context: Context) {
        if (isRunning) return
        val id = selectedTemplateId ?: return
        templates = TemplateStore(context).remove(id)
        selectedTemplateId = null
        notification = "템플릿을 삭제했습니다. 현재 편집값은 유지됩니다."
    }

    fun clearNotification() { notification = null }
    fun notifyUser(message: String) { notification = message }

    fun addVideos(context: Context, selected: List<Uri>) {
        if (isRunning || isImporting || selected.isEmpty()) return
        if (files.size + selected.size > 250) {
            notification = "한 번에 최대 250개의 영상을 선택할 수 있습니다."
            return
        }
        isImporting = true
        viewModelScope.launch {
            try {
                val existing = files.map { it.uri }.toSet()
                val newRows = withContext(Dispatchers.IO) {
                    selected.distinctBy { it.toString() }
                        .filterNot { it.toString() in existing }
                        .map { uri ->
                            val name = runCatching {
                                context.contentResolver.query(
                                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                                )?.use { cursor ->
                                    if (cursor.moveToFirst()) cursor.getString(0) else null
                                }
                            }.getOrNull() ?: "선택한 영상"
                            try {
                                try {
                                    context.contentResolver.takePersistableUriPermission(
                                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                } catch (_: SecurityException) {
                                    // A provider may only grant this URI for the current session.
                                } catch (_: IllegalArgumentException) {
                                    // No persistable grant was supplied by the provider.
                                }
                                val metadata = MediaReader.metadata(context.applicationContext, uri)
                                val originalDate = OriginalMediaDates.resolve(context.applicationContext, uri)
                                BatchFile(uri.toString(), name, metadata.durationMs,
                                    metadata.hasAudio, originalDate)
                            } catch (error: Exception) {
                                BatchFile(uri.toString(), name, status = BatchFileStatus.SKIPPED,
                                    reason = "이 영상을 읽을 수 없습니다.")
                            }
                        }
                }
                files = files + newRows
                if (runState == BatchRunState.FINISHED) runState = BatchRunState.SETUP
                if (newRows.isEmpty()) notification = "이미 선택된 영상입니다."
            } catch (_: CancellationException) {
                // Selection is aborted with no changes.
            } catch (error: Exception) {
                notification = error.localizedMessage ?: "영상을 불러오지 못했습니다."
            } finally {
                isImporting = false
            }
        }
    }

    fun removeVideo(uri: String) {
        if (isRunning) return
        files = files.filterNot { it.uri == uri }
        runState = BatchRunState.SETUP
    }

    fun start(
        context: Context,
        retryOnly: Boolean = false,
        exportMode: ExportMode = ExportMode.ALLOW_REENCODE,
    ) {
        if (isRunning || isImporting) return
        val template = previewTemplate()
        if (template == null) {
            notification = "템플릿 이름 및 구간 시간을 확인해 주세요."
            return
        }
        if (files.isEmpty()) {
            notification = "일괄 편집할 영상을 선택해 주세요."
            return
        }
        val selectedDateMode = saveDateMode
        val selectedOriginalAction = originalAction
        performedOriginalAction = selectedOriginalAction
        originalStatus = OriginalActionStatus.NONE
        sourceUrisSavedThisRun = emptyList()
        if (!retryOnly) {
            files = files.map { file ->
                if (file.status == BatchFileStatus.SAVED) file else file.copy(
                    status = if (file.durationMs >= 100L) {
                        BatchFileStatus.WAITING
                    } else {
                        BatchFileStatus.SKIPPED
                    },
                    reason = if (file.durationMs < 100L) {
                        "영상 정보를 읽을 수 없습니다."
                    } else null,
                    progress = null,
                    savedOutputUri = null,
                )
            }
        }
        runState = BatchRunState.RUNNING
        notification = null
        job = viewModelScope.launch {
            try {
                val sourceRows = files.toList()
                for (row in sourceRows) {
                    // Previously completed files are never exported twice.
                    if (row.status == BatchFileStatus.SAVED) continue
                    if (row.durationMs < 100L) continue
                    if (selectedDateMode == SaveDateMode.ORIGINAL && row.originalDate == null) {
                        update(row.uri) {
                            it.copy(status=BatchFileStatus.SKIPPED,
                                reason="원본 날짜가 없어 건너뛰었습니다.")
                        }
                        continue
                    }
                    when (val plan = template.plan(row.uri, row.durationMs, row.hasAudio)) {
                        is BatchPlan.Skip -> {
                            update(row.uri) {
                                it.copy(status=BatchFileStatus.SKIPPED,reason=plan.reason)
                            }
                        }
                        is BatchPlan.Ready -> {
                            update(row.uri) {
                                it.copy(status=BatchFileStatus.RUNNING,reason=null,progress=null)
                            }
                            try {
                                val result = VideoExporter(context.applicationContext).export(
                                    timeline = plan.timeline,
                                    onProgress = { percent ->
                                        update(row.uri) { it.copy(progress=percent) }
                                    },
                                    onFinalizing = {
                                        update(row.uri) { it.copy(progress=null) }
                                    },
                                    saveDateMode = selectedDateMode,
                                    outputBaseName = row.displayName,
                                    exportMode = exportMode,
                                )
                                update(row.uri) {
                                    it.copy(status=BatchFileStatus.SAVED,progress=100,
                                        savedOutputUri=result.toString(),reason=null)
                                }
                                if (selectedOriginalAction != OriginalAction.KEEP) {
                                    sourceUrisSavedThisRun =
                                        sourceUrisSavedThisRun + row.uri
                                }
                            } catch (required: ReencodeRequiredException) {
                                update(row.uri) {
                                    it.copy(status=BatchFileStatus.SKIPPED,progress=null,
                                        reason="고속 저장을 지원하지 않아 건너뛰었습니다.")
                                }
                            } catch (cancelled: CancellationException) {
                                update(row.uri) {
                                    it.copy(status=BatchFileStatus.SKIPPED,progress=null,
                                        reason="사용자가 작업을 중지했습니다.")
                                }
                                throw cancelled
                            } catch (failure: Exception) {
                                update(row.uri) {
                                    it.copy(status=BatchFileStatus.FAILED,progress=null,
                                        reason=failure.localizedMessage ?: "영상 저장 실패")
                                }
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                files = files.map {
                    if (it.status == BatchFileStatus.WAITING) {
                        it.copy(status=BatchFileStatus.SKIPPED,reason="작업이 중지됐습니다.")
                    } else it
                }
            } finally {
                // Keep the selected action visible during rendering. The next
                // batch always resets to KEEP even if the prior run selected trash.
                originalAction = OriginalAction.KEEP
                runState = BatchRunState.FINISHED
                originalStatus = if (performedOriginalAction != OriginalAction.KEEP &&
                    originalCandidates.isNotEmpty()) OriginalActionStatus.READY
                else OriginalActionStatus.NONE
                job = null
            }
        }
    }

    private fun update(uri: String, block: (BatchFile) -> BatchFile) {
        files = files.map { if (it.uri == uri) block(it) else it }
        completedCount = files.count {
            it.status == BatchFileStatus.SAVED ||
                it.status == BatchFileStatus.SKIPPED ||
                it.status == BatchFileStatus.FAILED
        }
    }

    fun cancel() { job?.cancel() }

    fun promptOriginalApproval() {
        if (runState == BatchRunState.FINISHED &&
            originalStatus in listOf(OriginalActionStatus.READY, OriginalActionStatus.SKIPPED)
        ) originalStatus = OriginalActionStatus.CONFIRMING
    }

    fun keepOriginals() {
        if (originalStatus == OriginalActionStatus.CONFIRMING)
            originalStatus = OriginalActionStatus.SKIPPED
    }

    fun launchingOriginalApproval() {
        if (originalStatus == OriginalActionStatus.CONFIRMING)
            originalStatus = OriginalActionStatus.SYSTEM_PROMPT
    }

    fun originalApprovalResult(approved: Boolean) {
        if (originalStatus == OriginalActionStatus.SYSTEM_PROMPT)
            originalStatus = if (approved) OriginalActionStatus.COMPLETED
            else OriginalActionStatus.SKIPPED
    }

    fun originalApprovalUnsupported(message: String) {
        originalStatus = OriginalActionStatus.UNSUPPORTED
        notification = message
    }

    fun resetBatch() {
        if (isRunning) return
        files = emptyList()
        sourceUrisSavedThisRun = emptyList()
        completedCount = 0
        runState = BatchRunState.SETUP
        originalAction = OriginalAction.KEEP
        performedOriginalAction = OriginalAction.KEEP
        originalStatus = OriginalActionStatus.NONE
        notification = null
    }

    private fun formatSeconds(ms: Long): String =
        if (ms % 1_000L == 0L) (ms / 1_000L).toString()
        else (ms / 1_000.0).toString()
}
