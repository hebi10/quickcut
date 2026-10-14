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
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.VideoExporter
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.ReencodeRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class QuickCutPage {
    PICKER, IMPORTING, EDITOR, EXPORTING, COMPLETED,
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionStore = EditorSessionStore(application)
    private var sessionSaveJob: Job? = null
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
        if (timeline.isEmpty) {
            sessionStore.clear()
            hasRestorableSession = false
            return
        }
        val snapshot = snapshotSession()
        sessionSaveJob = viewModelScope.launch {
            delay(300L)
            sessionStore.save(snapshot)
            hasRestorableSession = true
        }
    }

    fun saveSessionOnStop() {
        if (page != QuickCutPage.EDITOR || timeline.isEmpty) return
        sessionSaveJob?.cancel()
        sessionStore.save(snapshotSession())
        hasRestorableSession = true
    }

    fun setQuickMode(enabled: Boolean) {
        quickMode = enabled
        if (page == QuickCutPage.EDITOR) saveSessionSoon()
    }

    fun restoreSavedSession(context: Context) {
        if (page != QuickCutPage.PICKER) return
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
                sessionStore.clear()
                hasRestorableSession = false
                page = QuickCutPage.PICKER
                reportError("이전 영상에 접근할 수 없어 편집을 복원하지 못했습니다. 영상을 다시 선택해 주세요.")
                return@launch
            }
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
    var isFinalizing by mutableStateOf(false)
        private set
    var completedUri by mutableStateOf<Uri?>(null)
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
    private val history = EditHistory()
    private var activeTrim: EditCheckpoint? = null

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

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
        finishActiveTrim()
        val previous = history.undo(checkpoint()) ?: return
        restoreCheckpoint(previous)
        updateHistoryButtons()
    }

    fun redo() {
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
        page = if (timeline.isEmpty) QuickCutPage.PICKER else QuickCutPage.EDITOR
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
            return offset >= 100L && clip.durationMs - offset >= 100L
        }

    fun importVideos(context: Context, uris: List<Uri>, append: Boolean = false) {
        if (uris.isEmpty() || page == QuickCutPage.EXPORTING || page == QuickCutPage.IMPORTING) return
        val previousPage = if (timeline.isEmpty) QuickCutPage.PICKER else QuickCutPage.EDITOR
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
                }
                val wasAppending = append && !timeline.isEmpty
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

    fun trimClip(id: Long, startMs: Long, endMs: Long) {
        if (timeline.clipAt(id) == null) return
        val edited = runCatching { timeline.updateRange(id, startMs, endMs) }.getOrNull() ?: return
        if (edited == timeline) return
        if (activeTrim == null) recordChange()
        timeline = edited
        playPositionMs = playPositionMs.coerceIn(0L, timeline.totalDurationMs)
        saveSessionSoon()
    }

    fun splitSelected() {
        if (!canSplitSelected) return
        val index = timeline.clips.indexOfFirst { it.id == selectedId }
        val clip = timeline.clips[index]
        val offset = playPositionMs - timeline.startOf(index)
        recordChange()
        timeline = timeline.split(clip.id, offset, nextId++)
        saveSessionSoon()
    }

    fun deleteSelected() {
        val id = selectedId ?: return
        val oldIndex = timeline.clips.indexOfFirst { it.id == id }
        recordChange()
        timeline = timeline.delete(id)
        if (timeline.isEmpty) {
            selectedId = null
            playPositionMs = 0L
            page = QuickCutPage.PICKER
        } else {
            selectedId = timeline.clips[oldIndex.coerceIn(0, timeline.clips.lastIndex)].id
            playPositionMs = timeline.startOf(
                timeline.clips.indexOfFirst { it.id == selectedId },
            )
        }
        saveSessionSoon()
    }

    fun moveClip(id: Long, direction: Int) {
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
        if (timeline.isEmpty || page != QuickCutPage.EDITOR) return
        val snapshot = timeline
        val sourceSnapshot = uniqueExportSources(snapshot)
        originalAction = selectedOriginalAction
        savedDateMode = selectedDateMode
        needsReencodeConsent = false
        originalActionStatus = OriginalActionStatus.NONE
        originalsFromSavedTimeline = emptyList()
        page = QuickCutPage.EXPORTING
        isFinalizing = false
        progressPercent = null
        exportJob = viewModelScope.launch {
            try {
                val uri = VideoExporter(context.applicationContext).export(
                    timeline = snapshot,
                    onProgress = { progressPercent = it },
                    onFinalizing = {
                        isFinalizing = true
                        progressPercent = null
                    },
                    saveDateMode = selectedDateMode,
                    exportMode = exportMode,
                )
                completedUri = uri
                originalsFromSavedTimeline = if (originalAction == OriginalAction.KEEP) {
                    emptyList()
                } else {
                    sourceSnapshot
                }
                originalActionStatus = if (originalAction == OriginalAction.KEEP) {
                    OriginalActionStatus.NONE
                } else {
                    OriginalActionStatus.READY
                }
                page = QuickCutPage.COMPLETED
                sessionSaveJob?.cancel()
                sessionStore.clear()
                hasRestorableSession = false
            } catch (_: CancellationException) {
                page = QuickCutPage.EDITOR
            } catch (required: ReencodeRequiredException) {
                needsReencodeConsent = true
                reportError(required.localizedMessage ?: "정밀 저장을 선택해 주세요.")
                page = QuickCutPage.EDITOR
            } catch (error: Exception) {
                reportError("저장에 실패했습니다. " +
                    (error.localizedMessage ?: "다시 시도해 주세요."))
                page = QuickCutPage.EDITOR
            } finally {
                isFinalizing = false
                progressPercent = null
                exportJob = null
            }
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
        showExitConfirmation = false
        sessionSaveJob?.cancel()
        sessionStore.clear()
        hasRestorableSession = false
        timeline = EditTimeline()
        quickMode = false
        needsReencodeConsent = false
        history.clear()
        activeTrim = null
        updateHistoryButtons()
        selectedId = null
        playPositionMs = 0L
        completedUri = null
        originalAction = OriginalAction.KEEP
        savedDateMode = SaveDateMode.CURRENT
        originalActionStatus = OriginalActionStatus.NONE
        originalsFromSavedTimeline = emptyList()
        errorMessage = null
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
