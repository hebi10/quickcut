package com.hebi.quickcut.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.VideoExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class QuickCutPage {
    PICKER, IMPORTING, EDITOR, EXPORTING, COMPLETED,
}

class EditorViewModel : ViewModel() {
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

    private var nextId = 1L
    private var exportJob: Job? = null

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
        timeline = edited
        playPositionMs = playPositionMs.coerceIn(0L, timeline.totalDurationMs)
    }

    fun splitSelected() {
        if (!canSplitSelected) return
        val index = timeline.clips.indexOfFirst { it.id == selectedId }
        val clip = timeline.clips[index]
        val offset = playPositionMs - timeline.startOf(index)
        timeline = timeline.split(clip.id, offset, nextId++)
    }

    fun deleteSelected() {
        val id = selectedId ?: return
        val oldIndex = timeline.clips.indexOfFirst { it.id == id }
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
    }

    fun moveClip(id: Long, direction: Int) {
        if (timeline.clipAt(id) == null) return
        timeline = timeline.move(id, direction)
        val index = timeline.clips.indexOfFirst { it.id == id }
        if (index >= 0) {
            selectedId = id
            playPositionMs = timeline.startOf(index)
        }
    }

    fun moveSelected(direction: Int) {
        selectedId?.let { moveClip(it, direction) }
    }

    fun toggleMute() {
        val id = selectedId ?: return
        timeline = timeline.toggleMute(id)
    }

    fun startExport(context: Context) {
        if (timeline.isEmpty || page != QuickCutPage.EDITOR) return
        val snapshot = timeline
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
                )
                completedUri = uri
                page = QuickCutPage.COMPLETED
            } catch (_: CancellationException) {
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

    fun requestExit() {
        if (page == QuickCutPage.EDITOR) showExitConfirmation = true
    }

    fun hideExitConfirmation() {
        showExitConfirmation = false
    }

    fun discardEditing() {
        showExitConfirmation = false
        timeline = EditTimeline()
        selectedId = null
        playPositionMs = 0L
        completedUri = null
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
