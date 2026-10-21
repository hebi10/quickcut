package com.hebi.quickcut.editor

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hebi.quickcut.export.ExportWorkMonitor
import com.hebi.quickcut.settings.QuickTrimSettings
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.VideoExporter
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.ExportReport
import com.hebi.quickcut.media.ExportDiagnostics
import com.hebi.quickcut.media.ReencodeRequiredException
import com.hebi.quickcut.media.OriginalMediaDates
import com.hebi.quickcut.media.OutputCleanupException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class QuickCutPage {
    PICKER, IMPORTING, EDITOR, EXPORTING, COMPLETED,
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionStore = EditorSessionStore(application)
    private val exportStore = SingleExportStore(application)
    private var sessionSaveJob: Job? = null
    var isRecoveringExport by mutableStateOf(exportStore.hasRecord())
        private set
    var hasUnresolvedExport by mutableStateOf(isRecoveringExport)
        private set
    var exportRecoveryNotice by mutableStateOf<String?>(null)
        private set
    var isTrimInputValid by mutableStateOf(true)
        private set
    val canStartExport: Boolean
        get() = isTrimInputValid && !timeline.isEmpty && page == QuickCutPage.EDITOR &&
            !isRecoveringExport && !hasUnresolvedExport && !ExportWorkMonitor.isRunning

    fun setTrimInputValidity(valid: Boolean) { isTrimInputValid = valid }

    var quickMode by mutableStateOf(false)
        private set
    var hasRestorableSession by mutableStateOf(sessionStore.load() != null)
        private set
    var needsReencodeConsent by mutableStateOf(false)
        private set

    private fun snapshotSession() = EditorSessionSnapshot(
        timeline = timeline,
        selectedId = selectedId,
        playPositionMs = playPositionMs,
        quickMode = quickMode,
    )

    private fun saveSessionSoon() {
        sessionSaveJob?.cancel()
        if (isRecoveringExport || hasUnresolvedExport) return
        if (timeline.isEmpty) {
            runCatching { sessionStore.clear() }.onSuccess { hasRestorableSession = false }
                .onFailure { reportError("편집 기록을 정리하지 못했습니다. 저장 공간을 확인해 주세요.") }
            return
        }
        val snapshot = snapshotSession()
        sessionSaveJob = viewModelScope.launch {
            delay(300L)
            runCatching { sessionStore.save(snapshot) }.onSuccess { hasRestorableSession = true }
                .onFailure { reportError("편집 내용을 기록하지 못했습니다. 저장 공간을 확인해 주세요.") }
        }
    }

    fun saveSessionOnStop() {
        if (page != QuickCutPage.EDITOR || timeline.isEmpty || isRecoveringExport || hasUnresolvedExport) return
        sessionSaveJob?.cancel()
        runCatching { sessionStore.save(snapshotSession()) }.onSuccess { hasRestorableSession = true }
            .onFailure { reportError("편집 내용을 기록하지 못했습니다. 저장 공간을 확인해 주세요.") }
    }

    fun changeQuickMode(enabled: Boolean) {
        quickMode = enabled
        isTrimInputValid = true
        if (page == QuickCutPage.EDITOR) saveSessionSoon()
    }

    fun restoreSavedSession(context: Context) {
        if (page != QuickCutPage.PICKER || isRecoveringExport || hasUnresolvedExport) return
        val old = sessionStore.load() ?: run {
            hasRestorableSession = false
            reportError("복원 가능한 편집 내용이 없습니다.")
            return
        }
        page = QuickCutPage.IMPORTING
        viewModelScope.launch {
            val valid = withContext(Dispatchers.IO) {
                runCatching {
                    old.timeline.clips.map { it.uri }.distinct().all { raw ->
                        val expected = old.timeline.clips.first { it.uri == raw }
                        val current = MediaReader.metadata(context.applicationContext, Uri.parse(raw))
                        kotlin.math.abs(current.durationMs - expected.sourceDurationMs) <= 1_500L &&
                            (!expected.hasAudio || current.hasAudio)
                    }
                }.getOrDefault(false)
            }
            if (!valid) {
                runCatching { sessionStore.clear() }.onSuccess { hasRestorableSession = false }
                page = QuickCutPage.PICKER
                reportError("이전 영상에 접근할 수 없어 편집을 복원하지 못했습니다. 영상을 다시 선택해 주세요.")
                return@launch
            }
            isTrimInputValid = true
            timeline = old.timeline
            selectedId = old.selectedId?.takeIf { timeline.clipAt(it) != null }
                ?: timeline.clips.first().id
            playPositionMs = old.playPositionMs.coerceIn(0L, timeline.totalDurationMs)
            quickMode = old.quickMode && timeline.clips.size == 1
            nextId = timeline.clips.maxOf { it.id } + 1L
            history.clear()
            activeTrim = null
            updateHistoryButtons()
            page = QuickCutPage.EDITOR
            saveSessionSoon()
        }
    }
    var page by mutableStateOf(QuickCutPage.PICKER)
        private set
    var timeline by mutableStateOf(EditTimeline())
        private set
    var selectedId by mutableStateOf<Long?>(null)
        private set
    var playPositionMs by mutableLongStateOf(0L)
        private set
    var progressPercent by mutableStateOf<Int?>(null)
        private set
    var exportStage by mutableStateOf(com.hebi.quickcut.media.ExportStage.PREPARING)
        private set
    var isFinalizing by mutableStateOf(false)
        private set
    var requestedExportMode by mutableStateOf(ExportMode.FAST_ONLY)
        private set
    var completedUri by mutableStateOf<Uri?>(null)
        private set
    var completedExportReport by mutableStateOf<ExportReport?>(null)
        private set
    var exportDiagnostics by mutableStateOf(ExportDiagnostics())
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var showExitConfirmation by mutableStateOf(false)
        private set
    var originalAction by mutableStateOf(OriginalAction.KEEP)
        private set
    var savedDateMode by mutableStateOf(SaveDateMode.CURRENT)
        private set
    var originalActionStatus by mutableStateOf(OriginalActionStatus.NONE)
        private set
    var originalsFromSavedTimeline by mutableStateOf<List<String>>(emptyList())
        private set

    private var nextId = 1L
    private var exportJob: Job? = null
    private var activeExportRecord: SingleExportSnapshot? = null
    private val history = EditHistory()
    private var activeTrim: EditCheckpoint? = null

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    init {
        if (hasUnresolvedExport) launchExportRecovery()
    }

    fun retryExportRecovery() {
        if (isRecoveringExport || exportJob != null || ExportWorkMonitor.isRunning || !hasUnresolvedExport) return
        launchExportRecovery()
    }

    private fun launchExportRecovery() {
        isRecoveringExport = true
        sessionSaveJob?.cancel()
        viewModelScope.launch {
            try {
                recoverExportRecord()
            } finally {
                isRecoveringExport = false
            }
        }
    }

    private suspend fun recoverExportRecord() {
        val result = withContext(Dispatchers.IO) {
            try {
                // A callback may have failed to persist its new URI. Retain that
                // in-memory handle before attempting recovery or permitting retry.
                activeExportRecord?.let(exportStore::save)
                SingleExportRecovery(exportStore, MediaStoreSingleExportAccess(getApplication())).recover()
            } catch (_: Exception) {
                SingleExportRecoveryResult.Blocked("이전 저장 기록을 확인하지 못했습니다. 저장 공간과 파일 접근을 확인한 뒤 다시 확인해 주세요.")
            }
        }
        when (result) {
            is SingleExportRecoveryResult.Published -> {
                originalAction = OriginalAction.KEEP
                originalsFromSavedTimeline = emptyList()
                originalActionStatus = OriginalActionStatus.NONE
                savedDateMode = if (result.snapshot.expectedDateMs != null) SaveDateMode.ORIGINAL else SaveDateMode.CURRENT
                finishExport(Uri.parse(requireNotNull(result.snapshot.outputUri)), emptyList())
                if (!hasUnresolvedExport) exportRecoveryNotice =
                    "이전에 저장이 완료된 영상을 복구했습니다. 중복 저장하지 않았으며 원본 처리 선택은 초기화됐습니다."
            }
            is SingleExportRecoveryResult.Blocked -> {
                hasUnresolvedExport = true
                exportRecoveryNotice = result.message
            }
            SingleExportRecoveryResult.NothingToRecover, SingleExportRecoveryResult.RetryAllowed -> {
                activeExportRecord = null
                hasUnresolvedExport = false
                hasRestorableSession = sessionStore.load() != null
                if (result == SingleExportRecoveryResult.RetryAllowed) exportRecoveryNotice =
                    "중단된 저장을 정리했습니다. 이전 편집 내용을 복원하거나 확인한 뒤 다시 저장해 주세요. 원본은 그대로 유지됩니다."
            }
        }
    }

    private fun checkpoint() = EditCheckpoint(timeline, selectedId, playPositionMs)

    private fun updateHistoryButtons() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    private fun finishActiveTrim() {
        val baseline = activeTrim ?: return
        activeTrim = null
        if (baseline.timeline != timeline) {
            history.record(baseline)
            updateHistoryButtons()
        }
    }

    fun beginTrim() {
        if (activeTrim == null && page == QuickCutPage.EDITOR) activeTrim = checkpoint()
    }

    fun finishTrim() {
        finishActiveTrim()
        saveSessionSoon()
    }

    private fun recordChange() {
        finishActiveTrim()
        history.record(checkpoint())
        updateHistoryButtons()
    }

    fun undo() {
        if (isRecoveringExport || hasUnresolvedExport) return
        finishActiveTrim()
        val previous = history.undo(checkpoint()) ?: return
        restoreCheckpoint(previous)
        updateHistoryButtons()
    }

    fun redo() {
        if (isRecoveringExport || hasUnresolvedExport) return
        finishActiveTrim()
        val next = history.redo(checkpoint()) ?: return
        restoreCheckpoint(next)
        updateHistoryButtons()
    }

    private fun restoreCheckpoint(restored: EditCheckpoint) {
        timeline = restored.timeline
        selectedId = restored.selectedId?.takeIf { timeline.clipAt(it) != null }
            ?: timeline.clips.firstOrNull()?.id
        playPositionMs = restored.playPositionMs.coerceIn(0L, timeline.totalDurationMs)
        page = QuickCutPage.EDITOR
        saveSessionSoon()
    }

    val selectedClip: VideoClip?
        get() = selectedId?.let(timeline::clipAt)

    val canSplitSelected: Boolean
        get() {
            val index = timeline.clips.indexOfFirst { it.id == selectedId }
            if (index < 0) return false
            val clip = timeline.clips[index]
            val offset = playPositionMs - timeline.startOf(index)
            return offset >= 100L && offset <= Long.MAX_VALUE / 1_000L &&
                clip.durationUs - offset * 1_000L >= 100_000L
        }

    fun importVideos(
        context: Context,
        uris: List<Uri>,
        append: Boolean = false,
        quickDefaults: QuickTrimSettings? = null,
    ) {
        if (uris.isEmpty() || page == QuickCutPage.EXPORTING || page == QuickCutPage.IMPORTING ||
            isRecoveringExport || hasUnresolvedExport) return
        val previousPage = if (page == QuickCutPage.EDITOR) QuickCutPage.EDITOR else QuickCutPage.PICKER
        val previousPosition = playPositionMs
        page = QuickCutPage.IMPORTING
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        runCatching {
                            try {
                                context.contentResolver.takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            } catch (_: SecurityException) {
                                // Some providers grant only session-scoped access.
                            } catch (_: IllegalArgumentException) {
                                // Persistable access is not available from this provider.
                            }
                            uri to MediaReader.metadata(context.applicationContext, uri)
                        }
                    }
                }
                val valid = results.mapNotNull { it.getOrNull() }
                if (valid.isEmpty()) {
                    reportError("선택한 영상을 열 수 없습니다. 다른 파일을 선택해 주세요.")
                    page = previousPage
                    return@launch
                }
                val clips = valid.map { (uri, meta) ->
                    VideoClip(
                        id = nextId++,
                        uri = uri.toString(),
                        sourceDurationMs = meta.durationMs,
                        hasAudio = meta.hasAudio,
                    )
                }.let { imported ->
                    if (quickDefaults == null || append || imported.size != 1) imported
                    else imported.map { clip ->
                        val leading = quickDefaults.leadingMs
                        val trailing = quickDefaults.trailingMs
                        val validRange = leading >= 0L && trailing >= 0L &&
                            leading <= clip.sourceDurationMs - 100L &&
                            trailing <= clip.sourceDurationMs - leading - 100L
                        clip.copy(
                            startMs = if (validRange) leading else clip.startMs,
                            endMs = if (validRange) clip.sourceDurationMs - trailing else clip.endMs,
                            muted = quickDefaults.muteAudio,
                        )
                    }
                }
                isTrimInputValid = true
                val wasAppending = append
                if (wasAppending) {
                    recordChange()
                } else {
                    history.clear()
                    activeTrim = null
                    updateHistoryButtons()
                }
                timeline = if (wasAppending) timeline.append(clips) else EditTimeline(clips)
                selectedId = if (wasAppending) selectedId ?: clips.first().id else clips.first().id
                playPositionMs = if (wasAppending) {
                    previousPosition.coerceIn(0L, timeline.totalDurationMs)
                } else {
                    0L
                }
                val rejected = uris.size - valid.size
                errorMessage = if (rejected > 0) {
                    "지원하지 않는 영상 " + rejected + "개를 제외하고 " +
                        valid.size + "개를 불러왔습니다."
                } else {
                    null
                }
                page = QuickCutPage.EDITOR
                saveSessionSoon()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError("영상을 불러올 수 없습니다. " +
                    (error.localizedMessage ?: "다른 파일을 선택해 주세요."))
                page = previousPage
            }
        }
    }

    fun selectClip(id: Long) {
        val index = timeline.clips.indexOfFirst { it.id == id }
        if (index < 0) return
        selectedId = id
        playPositionMs = timeline.startOf(index)
        saveSessionSoon()
    }

    fun seek(positionMs: Long) {
        playPositionMs = positionMs.coerceIn(0L, timeline.totalDurationMs)
    }

    fun trimSelected(startMs: Long, endMs: Long) {
        selectedId?.let { trimClip(it, startMs, endMs) }
    }

    fun applyFastCutSuggestion(suggestion: com.hebi.quickcut.media.FastCutSuggestion): Boolean {
        if (!canStartExport) return false
        val adjusted = suggestion.applyTo(timeline) ?: return false
        recordChange()
        timeline = adjusted
        needsReencodeConsent = false
        playPositionMs = playPositionMs.coerceIn(0L, timeline.totalDurationMs)
        saveSessionSoon()
        return true
    }

    fun trimClip(id: Long, startMs: Long, endMs: Long) {
        if (isRecoveringExport || hasUnresolvedExport) return
        if (timeline.clipAt(id) == null) return
        val edited = runCatching { timeline.updateRange(id, startMs, endMs) }.getOrNull() ?: return
        if (edited == timeline) return
        needsReencodeConsent = false
        if (activeTrim == null) recordChange()
        timeline = edited
        playPositionMs = playPositionMs.coerceIn(0L, timeline.totalDurationMs)
        saveSessionSoon()
    }

    fun splitSelected() {
        if (isRecoveringExport || hasUnresolvedExport) return
        if (!canSplitSelected) return
        val index = timeline.clips.indexOfFirst { it.id == selectedId }
        val clip = timeline.clips[index]
        val offset = playPositionMs - timeline.startOf(index)
        recordChange()
        timeline = timeline.split(clip.id, offset, nextId++)
        saveSessionSoon()
    }

    fun deleteSelected() {
        if (isRecoveringExport || hasUnresolvedExport) return
        val id = selectedId ?: return
        val oldIndex = timeline.clips.indexOfFirst { it.id == id }
        recordChange()
        timeline = timeline.delete(id)
        if (timeline.isEmpty) {
            selectedId = null
            playPositionMs = 0L
        } else {
            selectedId = timeline.clips[oldIndex.coerceIn(0, timeline.clips.lastIndex)].id
            playPositionMs = timeline.startOf(
                timeline.clips.indexOfFirst { it.id == selectedId },
            )
        }
        saveSessionSoon()
    }

    fun moveClip(id: Long, direction: Int) {
        if (isRecoveringExport || hasUnresolvedExport) return
        if (timeline.clipAt(id) == null) return
        val updated = timeline.move(id, direction)
        if (updated == timeline) return
        recordChange()
        timeline = updated
        val index = timeline.clips.indexOfFirst { it.id == id }
        if (index >= 0) {
            selectedId = id
            playPositionMs = timeline.startOf(index)
        }
        saveSessionSoon()
    }

    fun moveSelected(direction: Int) {
        selectedId?.let { moveClip(it, direction) }
    }

    fun toggleMute() {
        if (isRecoveringExport || hasUnresolvedExport) return
        val id = selectedId ?: return
        recordChange()
        timeline = timeline.toggleMute(id)
        saveSessionSoon()
    }

    fun startExport(
        context: Context,
        selectedOriginalAction: OriginalAction = OriginalAction.KEEP,
        selectedDateMode: SaveDateMode = SaveDateMode.CURRENT,
        exportMode: ExportMode = ExportMode.ALLOW_REENCODE,
    ) {
        if (!canStartExport) {
            reportError(if (isRecoveringExport || hasUnresolvedExport) "이전 저장 파일 확인을 먼저 완료해 주세요."
                else if (!isTrimInputValid) "시간 입력을 확인해 주세요. 최소 0.1초 이상 남겨야 합니다."
                else "다른 영상 저장이 진행 중이거나 저장할 영상이 없습니다.")
            return
        }
        val snapshot = timeline
        sessionSaveJob?.cancel()
        try {
            // Flush the latest edit before any output can be created, including
            // saves started within the editor's normal debounce interval.
            sessionStore.save(snapshotSession())
            hasRestorableSession = true
        } catch (_: Exception) {
            reportError("편집 내용을 기록하지 못해 저장을 시작하지 않았습니다. 저장 공간을 확인해 주세요.")
            return
        }
        val workId = try {
            ExportWorkMonitor.begin(context, "QuickCut 영상 저장", ::cancelExport) {
                exportJob?.cancel()
                if (page == QuickCutPage.EXPORTING)
                    reportError("백그라운드 저장이 시스템에 의해 중단됐습니다. 앱을 열고 다시 시도해 주세요.")
            }
        } catch (error: Exception) {
            reportError(error.localizedMessage ?: "저장 서비스를 시작하지 못했습니다.")
            return
        }
        val sourceSnapshot = uniqueExportSources(snapshot)
        originalAction = selectedOriginalAction
        savedDateMode = selectedDateMode
        needsReencodeConsent = false
        requestedExportMode = exportMode
        completedUri = null
        completedExportReport = null
        exportDiagnostics = ExportDiagnostics()
        exportRecoveryNotice = null
        activeExportRecord = null
        originalActionStatus = OriginalActionStatus.NONE
        originalsFromSavedTimeline = emptyList()
        page = QuickCutPage.EXPORTING
        isFinalizing = false
        progressPercent = null
        exportStage = com.hebi.quickcut.media.ExportStage.PREPARING
        exportJob = viewModelScope.launch {
            try {
                ExportWorkMonitor.awaitReady(workId)
                val originalDate = if (selectedDateMode == SaveDateMode.ORIGINAL) withContext(Dispatchers.IO) {
                    OriginalMediaDates.resolve(context.applicationContext, Uri.parse(snapshot.clips.first().uri))
                        ?: throw java.io.IOException("원본에 확인 가능한 촬영/수정 날짜가 없어 원본 날짜로 저장할 수 없습니다.")
                } else null
                val initialRecord = SingleExportSnapshot(null, snapshot.totalDurationMs,
                    snapshot.clips.any { !it.muted && it.hasAudio }, originalDate?.epochMs,
                    expectedDurationUs = snapshot.totalDurationUs)
                activeExportRecord = initialRecord
                hasUnresolvedExport = true
                withContext(Dispatchers.IO) { exportStore.save(initialRecord) }
                var committedOutput: Uri? = null
                val uri = VideoExporter(context.applicationContext).export(
                    timeline = snapshot,
                    onProgress = {
                        progressPercent = it
                        ExportWorkMonitor.update(workId, "영상 처리 중", it)
                    },
                    onFinalizing = {
                        isFinalizing = true
                        progressPercent = null
                        ExportWorkMonitor.update(workId, "갤러리 저장 완료 처리 중", canCancel = false)
                    },
                    onOutputCreated = { output ->
                        val record = requireNotNull(activeExportRecord).copy(outputUri = output.toString())
                        activeExportRecord = record
                        // GalleryWriter cannot write a byte until this atomic save
                        // succeeds. A failure propagates so its cleanup runs.
                        exportStore.save(record)
                    },
                    onCommitted = {
                        committedOutput = it
                        finishExport(it, sourceSnapshot)
                    },
                    saveDateMode = selectedDateMode,
                    resolvedOriginalDate = originalDate,
                    exportMode = exportMode,
                    onDiagnostics = { exportDiagnostics = it },
                    onStage = { exportStage = it },
                    onReport = { report ->
                        if (committedOutput != null && page == QuickCutPage.COMPLETED &&
                            completedUri == committedOutput) completedExportReport = report
                    },
                )
                if (page != QuickCutPage.COMPLETED) finishExport(uri, sourceSnapshot)
            } catch (cancelled: CancellationException) {
                retainCleanupHandle(cancelled)
                if (page != QuickCutPage.COMPLETED) page = QuickCutPage.EDITOR
            } catch (required: ReencodeRequiredException) {
                needsReencodeConsent = true
                page = QuickCutPage.EDITOR
            } catch (error: Exception) {
                retainCleanupHandle(error)
                reportError("저장에 실패했습니다. " +
                    (error.localizedMessage ?: "다시 시도해 주세요.") +
                    exportDiagnostics.explanation().let { if (it.isBlank()) "" else "\n\n$it" })
                page = QuickCutPage.EDITOR
            } finally {
                if (page != QuickCutPage.COMPLETED && (activeExportRecord != null || hasUnresolvedExport)) {
                    isRecoveringExport = true
                    try {
                        // GalleryWriter cleanup has returned, even on cancellation.
                        // Confirm absence or retain the journal before enabling retry.
                        withContext(NonCancellable) { recoverExportRecord() }
                    } finally {
                        isRecoveringExport = false
                    }
                }
                isFinalizing = false
                progressPercent = null
                exportJob = null
                ExportWorkMonitor.finish(workId)
            }
        }
    }

    private fun finishExport(uri: Uri, sources: List<String>) {
        completedUri = uri
        originalsFromSavedTimeline = if (originalAction == OriginalAction.KEEP) emptyList() else sources
        originalActionStatus = if (originalAction == OriginalAction.KEEP) OriginalActionStatus.NONE
            else OriginalActionStatus.READY
        page = QuickCutPage.COMPLETED
        errorMessage = null
        sessionSaveJob?.cancel()
        // Publication was already verified. Persistence errors must never escape
        // onCommitted: that would make GalleryWriter roll back a published video.
        try {
            sessionStore.clear()
            hasRestorableSession = false
            exportStore.clear()
            activeExportRecord = null
            hasUnresolvedExport = false
        } catch (_: Exception) {
            hasUnresolvedExport = true
            exportRecoveryNotice = "영상은 저장됐지만 완료 기록을 정리하지 못했습니다. 저장 공간을 확인한 뒤 다시 확인해 주세요."
        }
    }

    private fun retainCleanupHandle(error: Throwable) {
        val cleanup = (error as? OutputCleanupException)
            ?: error.suppressed.filterIsInstance<OutputCleanupException>().firstOrNull()
        if (cleanup != null) {
            activeExportRecord = activeExportRecord?.copy(outputUri = cleanup.outputUri.toString())
            hasUnresolvedExport = true
        }
    }

    fun cancelExport() {
        if (!isFinalizing) exportJob?.cancel()
    }

    fun showOriginalDeletionConfirmation() {
        if (page == QuickCutPage.COMPLETED &&
            originalAction != OriginalAction.KEEP &&
            originalActionStatus in listOf(
                OriginalActionStatus.READY,
                OriginalActionStatus.SKIPPED,
                OriginalActionStatus.UNSUPPORTED,
            )
        ) {
            originalActionStatus = OriginalActionStatus.CONFIRMING
        }
    }

    fun skipOriginalDeletion() {
        if (originalActionStatus == OriginalActionStatus.CONFIRMING) {
            originalActionStatus = OriginalActionStatus.SKIPPED
        }
    }

    fun markOriginalSystemPrompt() {
        if (originalActionStatus == OriginalActionStatus.CONFIRMING) {
            originalActionStatus = OriginalActionStatus.SYSTEM_PROMPT
        }
    }

    fun onOriginalSystemResult(approved: Boolean) {
        if (originalActionStatus == OriginalActionStatus.SYSTEM_PROMPT) {
            originalActionStatus = if (approved) {
                OriginalActionStatus.COMPLETED
            } else {
                OriginalActionStatus.SKIPPED
            }
        }
    }

    fun markOriginalUnsupported(detail: String) {
        originalActionStatus = OriginalActionStatus.UNSUPPORTED
        reportError(detail)
    }

    fun requestExit() {
        if (page == QuickCutPage.EDITOR) showExitConfirmation = true
    }

    fun hideExitConfirmation() {
        showExitConfirmation = false
    }

    fun discardEditing() {
        if (isRecoveringExport || hasUnresolvedExport || page == QuickCutPage.EXPORTING) {
            reportError("이전 저장 파일 확인을 먼저 완료해 주세요.")
            return
        }
        showExitConfirmation = false
        sessionSaveJob?.cancel()
        try {
            sessionStore.clear()
        } catch (_: Exception) {
            reportError("편집 기록을 정리하지 못했습니다. 저장 공간을 확인해 주세요.")
            return
        }
        hasRestorableSession = false
        timeline = EditTimeline()
        quickMode = false
        isTrimInputValid = true
        needsReencodeConsent = false
        history.clear()
        activeTrim = null
        updateHistoryButtons()
        selectedId = null
        playPositionMs = 0L
        completedUri = null
        completedExportReport = null
        originalAction = OriginalAction.KEEP
        savedDateMode = SaveDateMode.CURRENT
        originalActionStatus = OriginalActionStatus.NONE
        originalsFromSavedTimeline = emptyList()
        errorMessage = null
        exportRecoveryNotice = null
        page = QuickCutPage.PICKER
    }

    fun startNew() = discardEditing()

    fun reportError(message: String) {
        errorMessage = message
    }

    fun dismissError() {
        errorMessage = null
    }
}


