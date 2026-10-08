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
        page = QuickCutPage.IMPORTING
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        } catch (_: SecurityException) {
                            // Temporary URI permission remains valid for this session.
                        }
                        val meta = MediaReader.metadata(context.applicationContext, uri)
                        VideoClip(
                            id = nextId++,
                            uri = uri.toString(),
                            sourceDurationMs = meta.durationMs,
                            hasAudio = meta.hasAudio,
                        )
                    }
                }
                timeline = if (append && !timeline.isEmpty) {
                    timeline.append(items)
                } else {
                    EditTimeline(items)
                }
                selectedId = if (append) selectedId ?: items.firstOrNull()?.id else items.firstOrNull()?.id
                playPositionMs = 0L
                errorMessage = null
                page = QuickCutPage.EDITOR
            } catch (error: Exception) {
                errorMessage = "영상을 불러올 수 없습니다. " +
                    (error.localizedMessage ?: "다른 파일을 선택해 주세요.")
                page = if (timeline.isEmpty) QuickCutPage.PICKER else QuickCutPage.EDITOR
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
        val id = selectedId ?: return
        val candidate = runCatching { timeline.updateRange(id, startMs, endMs) }.getOrNull() ?: return
        timeline = candidate
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

    fun moveSelected(direction: Int) {
        val id = selectedId ?: return
        timeline = timeline.move(id, direction)
        val index = timeline.clips.indexOfFirst { it.id == id }
        if (index >= 0) playPositionMs = timeline.startOf(index)
    }

    fun toggleMute() {
        val id = selectedId ?: return
        timeline = timeline.toggleMute(id)
    }

    fun startExport(context: Context) {
        if (timeline.isEmpty || page != QuickCutPage.EDITOR) return
        page = QuickCutPage.EXPORTING
        progressPercent = null
        exportJob = viewModelScope.launch {
            try {
                val uri = VideoExporter(context.applicationContext).export(timeline) {
                    progressPercent = it
                }
                completedUri = uri
                page = QuickCutPage.COMPLETED
            } catch (_: CancellationException) {
                page = QuickCutPage.EDITOR
            } catch (error: Exception) {
                errorMessage = "저장에 실패했습니다. " +
                    (error.localizedMessage ?: "다시 시도해 주세요.")
                page = QuickCutPage.EDITOR
            } finally {
                progressPercent = null
                exportJob = null
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
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
