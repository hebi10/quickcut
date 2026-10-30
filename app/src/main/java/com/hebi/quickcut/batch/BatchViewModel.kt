package com.hebi.quickcut.batch

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hebi.quickcut.export.ExportWorkMonitor
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.OriginalActionStatus
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.OriginalMediaDates
import com.hebi.quickcut.media.OriginalTimestamp
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.media.VideoExporter
import com.hebi.quickcut.media.ExportPlanner
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.ExportStage
import com.hebi.quickcut.media.ExportReport
import com.hebi.quickcut.media.ExportDiagnostics
import com.hebi.quickcut.media.ReencodeRequiredException
import com.hebi.quickcut.media.exportFailureMessage
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.settings.BatchDraftSettings
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val outputExpectedDurationMs: Long? = null,
    val outputExpectedAudio: Boolean? = null,
    val outputExpectedDateMs: Long? = null,
    val exportReport: ExportReport? = null,
    val diagnosticId: String? = null,
)

data class BatchExportOverview(
    val ready: Int,
    val fast: Int,
    val needsEncoding: Int,
    val skipped: Int,
    val encodingFiles: List<String>,
    val skippedFiles: List<String>,
    val adjustments: List<BatchStartAdjustment>,
    val unadjustableFiles: List<String>,
    val reviewedFiles: List<BatchReviewedFile> = emptyList(),
) {
    val adjustedFast: Int get() = if (reviewedFiles.isEmpty()) fast + adjustments.size
        else reviewedFiles.count { it.fastAvailable || it.canRecommend }
    val adjustedSkips: Int get() = if (reviewedFiles.isEmpty()) skipped + needsEncoding - adjustments.size
        else reviewedFiles.size - adjustedFast
}

class BatchViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = QuickCutSettings(application)
    private val lastDraft = settings.loadBatch()
    private val sessionStore = BatchSessionStore(application)
    private val initialLoad = runCatching { sessionStore.load() }
    private val restored = initialLoad.getOrNull()
    private var journalUnreadable by mutableStateOf(initialLoad.isFailure)
    private var persistenceFailed by mutableStateOf(false)
    private var activeTemplate: BatchTemplate? = restored?.template
    private var activeDateMode = restored?.dateMode ?: settings.loadSaveDate()
    internal var reviewGeneration by mutableLongStateOf(0L)
        private set
    private var pendingReview: ExportReview? = null

    private data class ReviewState(
        val name: String,
        val mode: BatchClipMode,
        val leading: String,
        val trailing: String,
        val muted: Boolean,
        val templateId: String?,
        val dateMode: SaveDateMode,
        val originalAction: OriginalAction,
        val files: List<BatchFile>,
        val runState: BatchRunState,
        val retryOnly: Boolean,
    )

    private data class ExportReview(
        val overview: BatchExportOverview,
        val state: ReviewState,
        val generation: Long,
    )

    private fun reviewState(retryOnly: Boolean) = ReviewState(
        draftName, draftMode, draftLeadingSeconds, draftTrailingSeconds, draftMute,
        selectedTemplateId, saveDateMode, originalAction, files.toList(), runState, retryOnly,
    )

    private fun invalidateReview() {
        pendingReview = null
        reviewGeneration++
    }

    suspend fun inspectExport(
        context: Context,
        retryOnly: Boolean,
        onFileProgress: (Int?) -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BatchExportOverview {
        invalidateReview()
        val generation = reviewGeneration
        check(!isRecovering && !isImporting && !isRunning && !hasUnresolvedRecovery) {
            "파일을 확인하거나 저장 중입니다. 잠시 후 다시 시도해 주세요."
        }
        val reviewedState = reviewState(retryOnly)
        val template = previewTemplate()
            ?: throw IllegalArgumentException("편집 설정을 확인해 주세요.")
        val dateMode = saveDateMode
        val targets = files.filter { it.status != BatchFileStatus.SAVED &&
            (!retryOnly || it.status == BatchFileStatus.FAILED ||
                it.status == BatchFileStatus.SKIPPED)
        }
        var fast = 0
        var encoded = 0
        var skipped = 0
        val encodingFiles = mutableListOf<String>()
        val skippedFiles = mutableListOf<String>()
        val adjustments = mutableListOf<BatchStartAdjustment>()
        val unadjustableFiles = mutableListOf<String>()
        val reviewedFiles = mutableListOf<BatchReviewedFile>()
        for ((index, file) in targets.withIndex()) {
            onProgress(index + 1, targets.size)
            onFileProgress(null)
            if (file.durationMs < 100L) {
                skipped++
                val reason = file.reason ?: "영상 정보를 읽을 수 없습니다."
                skippedFiles += file.displayName + " · " + reason
                reviewedFiles += BatchReviewedFile(file.uri, file.displayName, null, unavailableReason = reason)
                continue
            }
            if (dateMode == SaveDateMode.ORIGINAL && file.originalDate == null) {
                skipped++
                skippedFiles += file.displayName + " · 원본 날짜 없음"
                reviewedFiles += BatchReviewedFile(file.uri, file.displayName, null,
                    unavailableReason = "원본 날짜가 없어 건너뛰었습니다.")
                continue
            }
            when (val plan = template.plan(file.uri, file.durationMs, file.hasAudio)) {
                is BatchPlan.Skip -> {
                    skipped++
                    skippedFiles += file.displayName + " · " + plan.reason
                    reviewedFiles += BatchReviewedFile(file.uri, file.displayName, null,
                        unavailableReason = plan.reason)
                }
                is BatchPlan.Ready -> {
                    val assessment = ExportPlanner.analyze(context, plan.timeline, dateMode,
                        onProgress = onFileProgress)
                    val suggestion = assessment.suggestion?.takeIf { it.applyTo(plan.timeline) != null }
                    reviewedFiles += BatchReviewedFile(file.uri, file.displayName, plan.timeline,
                        suggestion, assessment.fastAvailable, explanation = assessment.explanation)
                    if (assessment.fastAvailable) {
                        fast++
                    } else {
                        encoded++
                        encodingFiles += file.displayName + " · " + assessment.explanation
                        val adjustment = BatchFastAdjustmentPolicy.fromSuggestion(
                            file.displayName, suggestion,
                        )
                        if (adjustment != null) adjustments += adjustment
                        if (suggestion == null) {
                            unadjustableFiles += file.displayName + " · " + assessment.explanation
                        }
                    }
                }
            }
        }
        check(generation == reviewGeneration && reviewedState == reviewState(retryOnly) &&
            !isRecovering && !isImporting && !isRunning) {
            "분석 중 설정이나 파일이 변경됐습니다. 저장 방식을 다시 분석해 주세요."
        }
        val overview = BatchExportOverview(
            ready = fast + encoded,
            fast = fast,
            needsEncoding = encoded,
            skipped = skipped,
            encodingFiles = encodingFiles,
            skippedFiles = skippedFiles,
            adjustments = adjustments,
            unadjustableFiles = unadjustableFiles,
            reviewedFiles = reviewedFiles.toList(),
        )
        pendingReview = ExportReview(overview, reviewedState, generation)
        return overview
    }

    fun isReviewCurrent(overview: BatchExportOverview, retryOnly: Boolean): Boolean {
        val review = pendingReview
        return review != null && review.overview === overview &&
            review.generation == reviewGeneration &&
            review.state == reviewState(retryOnly) && !isRunning && !isRecovering && !isImporting && !hasUnresolvedRecovery
    }

    private fun requireCurrentReview(overview: BatchExportOverview, retryOnly: Boolean): Boolean {
        if (isReviewCurrent(overview, retryOnly)) return true
        invalidateReview()
        notification = "설정이나 파일 또는 재시도 상태가 변경됐습니다. 저장 방식을 다시 분석해 주세요."
        return false
    }

    /** A complete map explicitly authorizes the requested mode of every reviewed file. */
    fun startReviewed(
        context: Context,
        overview: BatchExportOverview,
        retryOnly: Boolean,
        choices: Map<String, BatchExportChoice>,
    ) {
        if (!requireCurrentReview(overview, retryOnly)) return
        val resolved = BatchExportChoicePolicy.resolve(overview.reviewedFiles, choices.toMap())
        if (resolved == null) {
            invalidateReview()
            notification = "파일별 저장 선택을 확인할 수 없습니다. 저장 방식을 다시 분석해 주세요."
            return
        }
        startExport(context, retryOnly, ExportMode.ALLOW_REENCODE, resolved.associateBy { it.uri })
    }

    /** Compatibility shortcuts are still bound to the exact, unconsumed review shown to the user. */
    fun startReviewed(
        context: Context,
        overview: BatchExportOverview,
        retryOnly: Boolean,
        exportMode: ExportMode = ExportMode.ALLOW_REENCODE,
        adjustStarts: Boolean = false,
    ) {
        if (!requireCurrentReview(overview, retryOnly)) return
        if (adjustStarts && (exportMode != ExportMode.FAST_ONLY || overview.reviewedFiles.none { it.canRecommend })) {
            invalidateReview()
            notification = "위치 조정 내용을 다시 분석하고 확인해 주세요."
            return
        }
        val choices = overview.reviewedFiles.associate { file ->
            file.uri to when {
                file.requested == null -> BatchExportChoice.SKIP
                exportMode == ExportMode.ALLOW_REENCODE || file.fastAvailable -> BatchExportChoice.EXACT
                adjustStarts && file.canRecommend -> BatchExportChoice.RECOMMENDED
                else -> BatchExportChoice.SKIP
            }
        }
        val resolved = BatchExportChoicePolicy.resolve(overview.reviewedFiles, choices)
        if (resolved == null) {
            invalidateReview()
            notification = "위치 조정 내용을 다시 분석하고 확인해 주세요."
            return
        }
        val exports = resolved.map { result ->
            if (exportMode == ExportMode.FAST_ONLY && result.timeline != null) {
                result.copy(exportMode = ExportMode.FAST_ONLY)
            } else if (result.choice == BatchExportChoice.SKIP && result.requested != null) {
                result.copy(skipReason = "고속 저장을 지원하지 않아 건너뛰었습니다. " +
                    overview.reviewedFiles.first { it.uri == result.uri }.explanation.orEmpty())
            } else result
        }
        startExport(context, retryOnly, exportMode, exports.associateBy { it.uri })
    }

    var templates by mutableStateOf<List<BatchTemplate>>(emptyList())
        private set
    var files by mutableStateOf<List<BatchFile>>(restored?.files ?: emptyList())
        private set
    var runState by mutableStateOf(if (restored == null) BatchRunState.SETUP else BatchRunState.FINISHED)
        private set
    var isRecovering by mutableStateOf(restored != null)
        private set
    var isFinalizing by mutableStateOf(false)
        private set
    var currentExportStage by mutableStateOf(ExportStage.PREPARING)
        private set
    var isImporting by mutableStateOf(false)
        private set
    var notification by mutableStateOf<String?>(if (journalUnreadable)
        "이전 일괄 작업 기록을 읽을 수 없습니다. 저장 공간과 파일 접근을 확인한 뒤 다시 시도해 주세요." else null)
        private set
    var completedCount by mutableStateOf(0)
        private set
    var selectedTemplateId by mutableStateOf<String?>(lastDraft.selectedTemplateId)
        private set

    private var draftNameState by mutableStateOf(lastDraft.name)
    var draftName: String
        get() = draftNameState
        set(value) { if (value != draftNameState) { invalidateReview(); draftNameState = value } }
    private var draftModeState by mutableStateOf(lastDraft.mode)
    var draftMode: BatchClipMode
        get() = draftModeState
        set(value) { if (value != draftModeState) { invalidateReview(); draftModeState = value } }
    private var draftLeadingState by mutableStateOf(formatSeconds(lastDraft.leadingMs))
    var draftLeadingSeconds: String
        get() = draftLeadingState
        set(value) { if (value != draftLeadingState) { invalidateReview(); draftLeadingState = value } }
    private var draftTrailingState by mutableStateOf(formatSeconds(lastDraft.trailingMs))
    var draftTrailingSeconds: String
        get() = draftTrailingState
        set(value) { if (value != draftTrailingState) { invalidateReview(); draftTrailingState = value } }
    private var draftMuteState by mutableStateOf(lastDraft.muteAudio)
    var draftMute: Boolean
        get() = draftMuteState
        set(value) { if (value != draftMuteState) { invalidateReview(); draftMuteState = value } }
    private var saveDateState by mutableStateOf(settings.loadSaveDate())
    var saveDateMode: SaveDateMode
        get() = saveDateState
        set(value) { if (value != saveDateState) { invalidateReview(); saveDateState = value } }
    private var originalActionState by mutableStateOf(settings.loadOriginalAction())
    var originalAction: OriginalAction
        get() = originalActionState
        set(value) {
            if (value != originalActionState) {
                invalidateReview()
                settings.saveOriginalAction(value)
                originalActionState = value
            }
        }
    var performedOriginalAction by mutableStateOf(OriginalAction.KEEP)
        private set
    var originalStatus by mutableStateOf(OriginalActionStatus.NONE)
        private set

    private var stored = false
    private var job: Job? = null
    // Only files exported successfully in the current run are eligible
    // for an original-media operation. Prior runs are never included.
    private var sourceUrisSavedThisRun by mutableStateOf<List<String>>(emptyList())

    init {
        viewModelScope.launch {
            settings.observeOriginalAction().collect { action ->
                if (action != originalActionState) {
                    invalidateReview()
                    originalActionState = action
                }
            }
        }
        restored?.let(::restoreSnapshotSettings)
        if (restored != null) viewModelScope.launch {
            try {
                files = withContext(Dispatchers.IO) { restored.files.map { recoverFile(application, it) } }
                completedCount = files.count { it.status != BatchFileStatus.WAITING && it.status != BatchFileStatus.RUNNING }
                persistSession()
                notification = "이전 일괄 작업을 복구했습니다. 완료 파일은 유지되며 남은 작업은 설정을 확인한 뒤 다시 시작해 주세요. 복구된 이전 출력의 원본은 처리하지 않습니다."
            } catch (error: Exception) {
                notification = "이전 작업 확인에 실패했습니다. " + (error.localizedMessage ?: "파일을 다시 선택해 주세요.")
            } finally {
                isRecovering = false
            }
        }
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
            }.distinctUntilChanged().collect {
                settings.saveBatch(it)
                if (!isRunning && !isRecovering && !journalUnreadable && !persistenceFailed) runCatching { persistSession() }
                    .onFailure { notification = "작업 목록을 기록하지 못했습니다. 저장 공간을 확인해 주세요." }
            }
        }
        viewModelScope.launch {
            snapshotFlow { saveDateMode }.distinctUntilChanged().collect {
                settings.saveSaveDate(it)
                if (!isRunning && !isRecovering && !journalUnreadable && !persistenceFailed) runCatching { persistSession() }
            }
        }
    }

    /** A corrupt/unwritten journal must never be overwritten by initial settings observers. */
    val hasUnresolvedRecovery: Boolean
        get() = journalUnreadable || persistenceFailed || files.any { it.hasUnresolvedOutput() }

    private fun restoreSnapshotSettings(snapshot: BatchSessionSnapshot) {
        activeTemplate = snapshot.template
        activeDateMode = snapshot.dateMode
        snapshot.template?.let {
            draftName = it.name
            draftMode = it.mode
            draftLeadingSeconds = formatSeconds(it.leadingMs)
            draftTrailingSeconds = formatSeconds(it.trailingMs)
            draftMute = it.muteAudio
            selectedTemplateId = it.id
        }
        saveDateMode = snapshot.dateMode
    }

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
        if (isRunning || isRecovering) return
        invalidateReview()
        selectedTemplateId = templateId
        val selected = templates.firstOrNull { it.id == templateId } ?: return
        draftName = selected.name
        draftMode = selected.mode
        draftLeadingSeconds = formatSeconds(selected.leadingMs)
        draftTrailingSeconds = formatSeconds(selected.trailingMs)
        draftMute = selected.muteAudio
    }

    private fun parseMillis(text: String): Long? = BatchTimeInput.parse(text)

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

    fun draftError(): String? {
        if (draftMode.usesLeading && parseMillis(draftLeadingSeconds) == null) {
            return "앞부분 시간을 확인해 주세요."
        }
        if (draftMode.usesTrailing && parseMillis(draftTrailingSeconds) == null) {
            return "뒷부분 시간을 확인해 주세요."
        }
        return currentDraft()?.checkValidity()
    }

    fun saveTemplate(context: Context, asNew: Boolean = false) {
        if (isRunning || isRecovering) return
        invalidateReview()
        val proposed = currentDraft()
        if (proposed == null) {
            notification = "시간은 분과 초로 나누어 최대 120분까지 입력해 주세요."
            return
        }
        proposed.checkValidity()?.let {
            notification = it
            return
        }
        try {
            val saved = if (asNew) proposed.copy(id = UUID.randomUUID().toString()) else proposed
            templates = TemplateStore(context).save(saved)
            selectedTemplateId = saved.id
            notification = "템플릿을 저장했습니다."
        } catch (e: Exception) {
            notification = e.localizedMessage ?: "템플릿을 저장하지 못했습니다."
        }
    }

    fun removeTemplate(context: Context) {
        if (isRunning || isRecovering) return
        invalidateReview()
        val id = selectedTemplateId ?: return
        try {
            templates = TemplateStore(context).remove(id)
            selectedTemplateId = null
            notification = "템플릿을 삭제했습니다. 현재 편집값은 유지됩니다."
        } catch (e: Exception) {
            notification = e.localizedMessage ?: "템플릿을 삭제하지 못했습니다."
        }
    }

    fun clearNotification() { notification = null }
    fun notifyUser(message: String) { notification = message }

    fun addVideos(context: Context, selected: List<Uri>) {
        if (isRunning || isRecovering || isImporting || selected.isEmpty()) return
        if (journalUnreadable || persistenceFailed) { recoveryNotice(); return }
        invalidateReview()
        if (files.size + selected.distinctBy { it.toString() }.count { uri -> files.none { it.uri == uri.toString() } } > 250) {
            notification = "한 번에 최대 250개의 영상을 선택할 수 있습니다."
            return
        }
        isImporting = true
        viewModelScope.launch {
            try {
                val existing = files.associateBy { it.uri }
                val refreshable = existing.filterValues { it.status != BatchFileStatus.SAVED && it.durationMs < 100L }
                val newRows = withContext(Dispatchers.IO) {
                    selected.distinctBy { it.toString() }
                        .filter { it.toString() !in existing || it.toString() in refreshable }
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
                val refreshed = newRows.associateBy { it.uri }.toMutableMap()
                files = files.map { previous ->
                    refreshed.remove(previous.uri)?.let { fresh -> fresh.copy(
                        status = if (previous.hasUnresolvedOutput()) BatchFileStatus.SKIPPED else fresh.status,
                        reason = if (previous.hasUnresolvedOutput()) previous.reason else fresh.reason,
                        savedOutputUri = previous.savedOutputUri,
                        outputExpectedDurationMs = previous.outputExpectedDurationMs,
                        outputExpectedAudio = previous.outputExpectedAudio,
                        outputExpectedDateMs = previous.outputExpectedDateMs,
                        diagnosticId = previous.diagnosticId) } ?: previous
                } + refreshed.values
                persistSession()
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
        if (isRunning || isRecovering || isImporting) return
        if (journalUnreadable || persistenceFailed) { recoveryNotice(); return }
        val row = files.firstOrNull { it.uri == uri } ?: return
        invalidateReview()
        isImporting = true
        viewModelScope.launch {
            try {
                if (row.status != BatchFileStatus.SAVED && row.savedOutputUri != null) {
                    val checked = withContext(Dispatchers.IO) { recoverFile(getApplication(), row) }
                    check(checked.savedOutputUri == null || checked.status == BatchFileStatus.SAVED) {
                        "미완성 출력 파일을 정리하지 못했습니다. 다시 시도해 주세요."
                    }
                }
                val remaining = files.filterNot { it.uri == uri }
                sessionStore.save(BatchSessionSnapshot(remaining, previewTemplate() ?: activeTemplate,
                    saveDateMode, BatchRunState.SETUP))
                files = remaining
                runState = BatchRunState.SETUP
                completedCount = files.count { it.status != BatchFileStatus.WAITING && it.status != BatchFileStatus.RUNNING }
            } catch (error: Exception) {
                notification = "선택 영상을 제거하지 못했습니다. " + (error.localizedMessage ?: "저장 공간을 확인해 주세요.")
            } finally { isImporting = false }
        }
    }

    fun start(
        context: Context,
        retryOnly: Boolean = false,
        exportMode: ExportMode = ExportMode.ALLOW_REENCODE,
    ) {
        startExport(context, retryOnly, exportMode)
    }

    private fun startExport(
        context: Context,
        retryOnly: Boolean,
        exportMode: ExportMode,
        reviewedExports: Map<String, BatchReviewedExport>? = null,
    ) {
        if (isRunning || isRecovering || isImporting) return
        if (hasUnresolvedRecovery) { recoveryNotice(); return }
        invalidateReview()
        if (ExportWorkMonitor.isRunning) {
            notification = "다른 영상 저장이 진행 중입니다. 완료 후 다시 시작해 주세요."
            return
        }
        val template = previewTemplate()
        if (template == null) {
            notification = "템플릿 이름 및 구간 시간을 확인해 주세요."
            return
        }
        if (files.isEmpty()) {
            notification = "일괄 편집할 영상을 선택해 주세요."
            return
        }
        val workId = try {
            ExportWorkMonitor.begin(context, "QuickCut 일괄 저장", ::cancel) {
                job?.cancel()
                notification = "백그라운드 저장이 시스템에 의해 중단됐습니다. 완료 파일은 유지하며 남은 작업은 다시 시작할 수 있습니다."
            }
        } catch (error: Exception) {
            notification = error.localizedMessage ?: "저장 서비스를 시작하지 못했습니다."
            return
        }
        activeTemplate = template
        activeDateMode = saveDateMode
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
                    savedOutputUri = file.savedOutputUri,
                    exportReport = null,
                )
            }
        }
        runState = BatchRunState.RUNNING
        notification = null
        job = viewModelScope.launch {
            try {
                persistSession()
                ExportWorkMonitor.awaitReady(workId)
                val sourceRows = files.toList()
                for (sourceRow in sourceRows) {
                    currentCoroutineContext().ensureActive()
                    var row = sourceRow
                    if (row.status != BatchFileStatus.SAVED && row.savedOutputUri != null) {
                        val recovered = withContext(Dispatchers.IO) { recoverFile(context.applicationContext, sourceRow) }
                        update(row.uri) { recovered }
                        row = recovered
                    }
                    // Uncertain handles remain tracked and can never create a duplicate output.
                    if (row.hasUnresolvedOutput()) continue
                    // Previously completed files are never exported twice.
                    if (row.status == BatchFileStatus.SAVED) continue
                    if (retryOnly && row.status != BatchFileStatus.FAILED && row.status != BatchFileStatus.SKIPPED) continue
                    val reviewedExport = reviewedExports?.get(row.uri)
                    if (reviewedExport?.choice == BatchExportChoice.SKIP) {
                        update(row.uri) {
                            it.copy(status = BatchFileStatus.SKIPPED, progress = null,
                                reason = reviewedExport.skipReason, exportReport = null)
                        }
                        continue
                    }
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
                            val timeline = if (reviewedExports == null) plan.timeline
                                else reviewedExport?.timelineFor(plan.timeline)
                            if (timeline == null) {
                                update(row.uri) {
                                    it.copy(status = BatchFileStatus.SKIPPED, progress = null,
                                        reason = "분석 이후 구간이 변경돼 건너뛰었습니다. 다시 분석해 주세요.")
                                }
                                continue
                            }
                            update(row.uri) {
                                it.copy(status=BatchFileStatus.RUNNING,reason=null,progress=null,
                                    exportReport=null)
                            }
                            isFinalizing = false
                            currentExportStage = ExportStage.PREPARING
                            val runPosition = completedCount + 1
                            ExportWorkMonitor.update(workId, "$runPosition/${files.size} · ${currentExportStage.label}")
                            var diagnostics = ExportDiagnostics()
                            try {
                                val expectedAudio = timeline.clips.any { it.hasAudio && !it.muted }
                                var committedOutput: Uri? = null
                                val result = VideoExporter(context.applicationContext).export(
                                    timeline = timeline,
                                    onDiagnosticStarted = { operationId ->
                                        update(row.uri) { it.copy(diagnosticId = operationId) }
                                    },
                                    onProgress = { percent ->
                                        update(row.uri, persist = false) { it.copy(progress=percent) }
                                        ExportWorkMonitor.update(workId,
                                            "$runPosition/${files.size} · ${currentExportStage.label}", percent,
                                            canCancel = !isFinalizing)
                                    },
                                    onFinalizing = {
                                        isFinalizing = true
                                        update(row.uri, persist = false) { it.copy(progress=null) }
                                        ExportWorkMonitor.update(workId, "${row.displayName} · 갤러리 저장 완료 처리 중", canCancel = false)
                                    },
                                    onOutputCreated = { output ->
                                        update(row.uri) { it.copy(savedOutputUri = output.toString(),
                                            outputExpectedDurationMs = timeline.totalDurationMs,
                                            outputExpectedAudio = expectedAudio,
                                            outputExpectedDateMs = if (selectedDateMode == SaveDateMode.ORIGINAL) row.originalDate?.epochMs else null,
                                            exportReport = null) }
                                    },
                                    onCommitted = { output ->
                                        committedOutput = output
                                        update(row.uri) { it.copy(status=BatchFileStatus.SAVED, progress=100,
                                            savedOutputUri=output.toString(), reason=null) }
                                        if (selectedOriginalAction != OriginalAction.KEEP)
                                            sourceUrisSavedThisRun = sourceUrisSavedThisRun + row.uri
                                    },
                                    saveDateMode = selectedDateMode,
                                    outputBaseName = row.displayName,
                                    exportMode = reviewedExport?.exportMode ?: exportMode,
                                    resolvedOriginalDate = row.originalDate,
                                    onStage = { stage ->
                                        if (stage != currentExportStage) {
                                            currentExportStage = stage
                                            update(row.uri, persist = false) { it.copy(progress = null) }
                                            ExportWorkMonitor.update(workId,
                                                "$runPosition/${files.size} · ${stage.label}",
                                                canCancel = !isFinalizing)
                                        }
                                    },
                                    onDiagnostics = { details ->
                                        diagnostics = details
                                        update(row.uri, persist = false) {
                                            it.copy(reason = details.explanation().takeIf(String::isNotBlank))
                                        }
                                    },
                                    onReport = { report ->
                                        // Reporting follows publication. A journal failure here
                                        // must not turn a saved video into a failed export.
                                        runCatching {
                                            update(row.uri) {
                                                if (committedOutput != null &&
                                                    it.status == BatchFileStatus.SAVED &&
                                                    it.savedOutputUri == committedOutput.toString())
                                                    it.copy(exportReport = report) else it
                                            }
                                        }.onFailure {
                                            notification = "영상은 저장했지만 처리 방식 기록을 보관하지 못했습니다."
                                        }
                                    },
                                )
                                if (files.first { it.uri == row.uri }.status != BatchFileStatus.SAVED) {
                                    update(row.uri) { it.copy(status=BatchFileStatus.SAVED,progress=100,
                                        savedOutputUri=result.toString(),reason=null) }
                                }
                            } catch (required: ReencodeRequiredException) {
                                update(row.uri) {
                                    it.copy(status=BatchFileStatus.SKIPPED,progress=null,
                                        reason="고속 저장을 지원하지 않아 건너뛰었습니다.\n" + diagnostics.explanation())
                                }
                            } catch (cancelled: CancellationException) {
                                if (files.first { it.uri == row.uri }.status != BatchFileStatus.SAVED) update(row.uri) {
                                    it.copy(status=BatchFileStatus.SKIPPED,progress=null,
                                        reason="작업이 중지됐습니다. 설정 확인 후 다시 시작해 주세요.")
                                }
                                throw cancelled
                            } catch (failure: Exception) {
                                update(row.uri) {
                                    it.copy(status=BatchFileStatus.FAILED,progress=null,
                                        reason=exportFailureMessage(failure) +
                                            diagnostics.explanation().let { text -> if (text.isBlank()) "" else "\n$text" })
                                }
                            } finally {
                                isFinalizing = false
                                ExportWorkMonitor.update(workId, "다음 영상 준비 중")
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
            } catch (error: Exception) {
                notification = "일괄 작업을 완료하지 못했습니다. " + exportFailureMessage(error)
                files = files.map {
                    if (it.status == BatchFileStatus.WAITING || it.status == BatchFileStatus.RUNNING)
                        it.copy(status=BatchFileStatus.FAILED, progress=null, reason=notification)
                    else it
                }
            } finally {
                isFinalizing = false
                runState = BatchRunState.FINISHED
                completedCount = files.count { it.status != BatchFileStatus.WAITING && it.status != BatchFileStatus.RUNNING }
                originalStatus = if (performedOriginalAction != OriginalAction.KEEP &&
                    originalCandidates.isNotEmpty()) OriginalActionStatus.READY
                else OriginalActionStatus.NONE
                job = null
                runCatching { persistSession() }.onFailure {
                    notification = "작업 결과를 기록하지 못했습니다. 저장 공간을 확인해 주세요."
                }
                ExportWorkMonitor.finish(workId)
            }
        }
    }

    private fun update(uri: String, persist: Boolean = true, block: (BatchFile) -> BatchFile) {
        files = files.map { if (it.uri == uri) block(it) else it }
        completedCount = files.count {
            it.status == BatchFileStatus.SAVED ||
                it.status == BatchFileStatus.SKIPPED ||
                it.status == BatchFileStatus.FAILED
        }
        if (persist) persistSession()
    }

    fun cancel() { if (!isFinalizing) job?.cancel() }

    fun promptOriginalApproval() {
        if (runState == BatchRunState.FINISHED &&
            originalCandidates.isNotEmpty() && originalStatus == OriginalActionStatus.SKIPPED
        ) originalStatus = OriginalActionStatus.READY
    }

    fun launchingOriginalApproval() {
        if (originalStatus == OriginalActionStatus.READY)
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
        if (isRunning || isRecovering || isImporting) return
        if (journalUnreadable || persistenceFailed) { recoveryNotice(); return }
        invalidateReview()
        val tracked = files.filter { it.status != BatchFileStatus.SAVED && it.savedOutputUri != null }
        if (tracked.isEmpty()) {
            runCatching { clearBatch() }.onFailure {
                notification = "이전 작업 기록을 정리하지 못했습니다. 저장 공간을 확인한 뒤 다시 시도해 주세요."
            }
            return
        }
        isImporting = true
        viewModelScope.launch {
            try {
                val checked = withContext(Dispatchers.IO) { tracked.map { recoverFile(getApplication(), it) } }
                check(checked.none { it.savedOutputUri != null && it.status != BatchFileStatus.SAVED }) {
                    "미완성 출력 파일을 정리하지 못했습니다. 파일 접근을 확인하고 다시 시도해 주세요."
                }
                clearBatch()
            } catch (error: Exception) {
                notification = error.localizedMessage ?: "이전 작업을 정리하지 못했습니다."
            } finally { isImporting = false }
        }
    }

    private fun clearBatch() {
        // Do not discard the in-memory retry handles if clearing the journal fails.
        sessionStore.clear()
        files = emptyList()
        sourceUrisSavedThisRun = emptyList()
        completedCount = 0
        runState = BatchRunState.SETUP
        performedOriginalAction = OriginalAction.KEEP
        originalStatus = OriginalActionStatus.NONE
        notification = null
    }

    private fun persistSession() {
        check(!journalUnreadable) { "이전 일괄 작업 기록을 읽을 수 없습니다." }
        try {
            sessionStore.save(BatchSessionSnapshot(files,
                if (isRunning) activeTemplate else previewTemplate() ?: activeTemplate,
                if (isRunning) activeDateMode else saveDateMode, runState))
            persistenceFailed = false
        } catch (error: Exception) {
            persistenceFailed = true
            throw error
        }
    }

    private fun recoveryNotice() {
        notification = "이전 작업의 확인을 완료하지 못했습니다. 파일 접근과 저장 공간을 확인한 뒤 다시 시도해 주세요."
    }

    /** Explicit recheck keeps every journal handle until output and persistence are verified. */
    fun retryRecovery() {
        if (isRunning || isRecovering || isImporting) return
        invalidateReview()
        isRecovering = true
        viewModelScope.launch {
            try {
                val snapshot = if (journalUnreadable) withContext(Dispatchers.IO) { sessionStore.load() } else null
                if (journalUnreadable) {
                    files = snapshot?.files ?: emptyList()
                    snapshot?.let(::restoreSnapshotSettings)
                    runState = if (snapshot == null) BatchRunState.SETUP else BatchRunState.FINISHED
                    journalUnreadable = false
                }
                files = withContext(Dispatchers.IO) { files.map { recoverFile(getApplication(), it) } }
                persistSession()
                completedCount = files.count { it.status != BatchFileStatus.WAITING && it.status != BatchFileStatus.RUNNING }
                if (hasUnresolvedRecovery) recoveryNotice() else notification = "이전 작업 확인을 완료했습니다."
            } catch (_: Exception) { recoveryNotice() }
            finally { isRecovering = false }
        }
    }

    private fun recoverFile(context: Context, row: BatchFile): BatchFile =
        BatchFileRecovery(context).recover(row)

    private fun formatSeconds(ms: Long): String =
        if (ms % 1_000L == 0L) (ms / 1_000L).toString()
        else (ms / 1_000.0).toString()
}


