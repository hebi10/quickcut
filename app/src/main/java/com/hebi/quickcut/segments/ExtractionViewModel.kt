package com.hebi.quickcut.segments

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hebi.quickcut.editor.MediaStoreSingleExportAccess
import com.hebi.quickcut.editor.SingleExportOutputState
import com.hebi.quickcut.editor.SingleExportSnapshot
import com.hebi.quickcut.export.ExportWorkMonitor
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.ExportPlanner
import com.hebi.quickcut.media.ExportStage
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.ReencodeRequiredException
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.media.VideoExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExtractionViewModel(
    application: Application,
    private val store: ExtractionSessionStore = ExtractionSessionStore(application),
) : AndroidViewModel(application) {
    private val app = application
    private var job: Job? = null
    private var reviewedFast: Map<Long, Boolean>? = null
    private var reviewedRanges: List<ExtractionItem>? = null

    var session by mutableStateOf<ExtractionSession?>(null)
        private set
    var selectedId by mutableStateOf<Long?>(null)
        private set
    var importing by mutableStateOf(false)
        private set
    var recovering by mutableStateOf(false)
        private set
    var inspecting by mutableStateOf(false)
        private set
    var exporting by mutableStateOf(false)
        private set
    var finalizing by mutableStateOf(false)
        private set
    var requiresReencodeApproval by mutableStateOf(false)
        private set
    var activeIndex by mutableStateOf(0)
        private set
    var progressPercent by mutableStateOf<Int?>(null)
        private set
    var currentStage by mutableStateOf(ExportStage.PREPARING)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    private var journalUnreadable by mutableStateOf(false)

    val busy: Boolean get() = importing || recovering || inspecting || exporting
    val selected: ExtractionItem? get() = session?.items?.firstOrNull { it.range.id == selectedId }
    val blocked: Boolean get() = journalUnreadable ||
        (session?.items?.any { it.status == ExtractionStatus.BLOCKED } == true)

    init {
        val restored = runCatching { store.load() }
        if (restored.isFailure) {
            journalUnreadable = true
            notice = "이전 구간 기록을 읽지 못했습니다. 안전 확인 전에는 저장을 시작할 수 없습니다."
        } else {
            session = restored.getOrNull()
            selectedId = session?.items?.firstOrNull()?.range?.id
            if (session?.items?.any { it.outputUri != null || it.status == ExtractionStatus.SAVING } == true) {
                recoverOutputs()
            }
        }
    }

    fun dismissNotice() { notice = null }
    fun selectRange(id: Long) {
        if (!busy && session?.items?.any { it.range.id == id } == true) selectedId = id
    }

    fun importVideo(context: Context, uri: Uri) {
        if (busy || ExportWorkMonitor.isRunning || blocked) {
            notice = "저장 중이거나 확인되지 않은 결과가 있습니다. 먼저 현재 작업을 확인해 주세요."
            return
        }
        importing = true
        viewModelScope.launch {
            try {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val data = withContext(Dispatchers.IO) {
                    val metadata = MediaReader.metadata(context.applicationContext, uri)
                    val name = context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }?.takeIf { it.isNotBlank() } ?: "선택한 영상.mp4"
                    name to metadata
                }
                val newSession = ExtractionSession(
                    sourceUri = uri.toString(), sourceName = data.first,
                    durationMs = data.second.durationMs, hasAudio = data.second.hasAudio,
                    items = ExtractionPlanner.initial(data.second.durationMs).map(::ExtractionItem),
                )
                persist(newSession)
                selectedId = newSession.items.first().range.id
                notice = null
            } catch (error: Exception) {
                notice = error.localizedMessage ?: "영상을 읽지 못했습니다. 다른 영상을 선택해 주세요."
            } finally {
                importing = false
            }
        }
    }

    fun addSegment(atMs: Long) {
        val current = session ?: return
        if (busy || blocked) return
        val next = ExtractionPlanner.add(current.items.map { it.range },
            current.durationMs, atMs)
        if (next == null) {
            notice = "최대 30개까지 추가할 수 있으며, 완전히 같은 구간은 중복 추가할 수 없습니다."
            return
        }
        val row = ExtractionItem(next.last())
        runCatching { persist(current.copy(items = current.items + row)) }
            .onSuccess { selectedId = row.range.id }
            .onFailure { notice = "구간 기록 저장에 실패했습니다. 설정을 확인해 주세요." }
        invalidateReview()
    }

    fun updateSegment(id: Long, startMs: Long, endMs: Long): Boolean {
        val current = session ?: return false
        if (busy || blocked) return false
        val old = current.items.firstOrNull { it.range.id == id } ?: return false
        if (old.outputUri != null || old.status == ExtractionStatus.SAVED) return false
        val nextRanges = ExtractionPlanner.replace(current.items.map { it.range },
            id, startMs, endMs, current.durationMs) ?: return false
        return runCatching {
            persist(current.copy(items = current.items.map { item ->
                if (item.range.id == id) item.copy(
                    range = nextRanges.first { it.id == id },
                    status = ExtractionStatus.WAITING, message = null, reencodeNeeded = false
                ) else item
            }))
            invalidateReview()
        }.isSuccess
    }

    fun removeSegment(id: Long) {
        val current = session ?: return
        if (busy || blocked || current.items.size <= 1) return
        val row = current.items.firstOrNull { it.range.id == id } ?: return
        if (row.outputUri != null || row.status == ExtractionStatus.SAVED) {
            notice = "이미 저장한 구간은 기록에서 제거할 수 없습니다."
            return
        }
        runCatching { persist(current.copy(items = current.items.filterNot { it.range.id == id })) }
            .onSuccess {
                if (selectedId == id) selectedId = session?.items?.firstOrNull()?.range?.id
                invalidateReview()
            }
            .onFailure { notice = "구간 기록 저장에 실패했습니다." }
    }

    fun inspectBeforeSave() {
        val snapshot = session ?: return
        if (busy || blocked || ExportWorkMonitor.isRunning) {
            notice = "다른 작업이 진행 중이거나 확인되지 않은 결과가 있습니다."
            return
        }
        val pending = snapshot.items.filter { it.status != ExtractionStatus.SAVED && it.outputUri == null }
        if (pending.isEmpty()) {
            notice = "새로 저장할 구간이 없습니다."
            return
        }
        inspecting = true
        invalidateReview()
        viewModelScope.launch {
            try {
                val result = mutableMapOf<Long, Boolean>()
                for (item in pending) {
                    val timeline = ExtractionPlanner.timeline(
                        snapshot.sourceUri, snapshot.durationMs, snapshot.hasAudio, item.range
                    )
                    val plan = ExportPlanner.analyze(app, timeline, SaveDateMode.CURRENT)
                    result[item.range.id] = plan.fastAvailable && !item.reencodeNeeded
                }
                if (session != snapshot) {
                    notice = "구간이 변경되었습니다. 다시 저장 방식을 확인해 주세요."
                    return@launch
                }
                reviewedRanges = pending
                reviewedFast = result
                if (result.values.all { it }) startReviewed(allowReencode = false)
                else requiresReencodeApproval = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.localizedMessage ?: "구간 저장 방식을 확인하지 못했습니다."
            } finally {
                inspecting = false
            }
        }
    }

    fun declineReencode() {
        requiresReencodeApproval = false
        invalidateReview()
    }

    fun approveReencode() {
        if (!requiresReencodeApproval) return
        requiresReencodeApproval = false
        startReviewed(allowReencode = true)
    }

    private fun startReviewed(allowReencode: Boolean) {
        val snapshot = session ?: return
        val reviewed = reviewedRanges ?: return
        val fastMap = reviewedFast ?: return
        if (exporting || blocked || ExportWorkMonitor.isRunning ||
            reviewed.any { row -> snapshot.items.none { it.range.id == row.range.id && it == row } } ||
            (!allowReencode && fastMap.values.any { !it })
        ) {
            notice = "저장 구간 또는 실행 상태가 변경되었습니다. 다시 확인해 주세요."
            invalidateReview()
            return
        }
        val workId = try {
            ExportWorkMonitor.begin(app, "QuickCut 여러 구간 저장", ::cancel) {
                job?.cancel()
                notice = "시스템이 저장을 중단했습니다. 완료된 파일은 보존됩니다."
            }
        } catch (error: Exception) {
            notice = error.localizedMessage ?: "저장 서비스를 시작하지 못했습니다."
            return
        }
        invalidateReview()
        exporting = true
        finalizing = false
        job = viewModelScope.launch {
            try {
                ExportWorkMonitor.awaitReady(workId)
                val candidateIds = reviewed.map { it.range.id }
                for (id in candidateIds) {
                    currentCoroutineContext().ensureActive()
                    val row = session?.items?.firstOrNull { it.range.id == id } ?: continue
                    if (row.outputUri != null || row.status == ExtractionStatus.SAVED) continue
                    val current = session ?: break
                    val index = current.items.indexOfFirst { it.range.id == id }
                    activeIndex = index + 1
                    progressPercent = null
                    currentStage = ExportStage.PREPARING
                    finalizing = false
                    try {
                        replaceItem(id) { it.copy(status = ExtractionStatus.SAVING, message = null) }
                        ExportWorkMonitor.update(workId, "${activeIndex}/${current.items.size} · 영상 저장 준비")
                        val timeline = ExtractionPlanner.timeline(
                            current.sourceUri, current.durationMs, current.hasAudio, row.range
                        )
                        val output = VideoExporter(app).export(
                            timeline = timeline,
                            onProgress = { percent ->
                                progressPercent = percent
                                ExportWorkMonitor.update(workId,
                                    "${activeIndex}/${current.items.size} · ${currentStage.label}",
                                    percent, !finalizing)
                            },
                            onFinalizing = {
                                finalizing = true
                                ExportWorkMonitor.update(workId,
                                    "${activeIndex}/${current.items.size} · 완료 확인", null, false)
                            },
                            outputBaseName = ExtractionPlanner.outputName(current.sourceName, index + 1),
                            saveDateMode = SaveDateMode.CURRENT,
                            exportMode = if (fastMap[id] == true) ExportMode.FAST_ONLY
                                else ExportMode.ALLOW_REENCODE,
                            onOutputCreated = { uri ->
                                replaceItem(id) { it.copy(outputUri = uri.toString()) }
                            },
                            onCommitted = { uri ->
                                replaceItem(id) { it.copy(
                                    status = ExtractionStatus.SAVED,
                                    outputUri = uri.toString(), message = null
                                ) }
                            },
                            onStage = { step ->
                                currentStage = step
                                ExportWorkMonitor.update(workId,
                                    "${activeIndex}/${current.items.size} · ${step.label}",
                                    null, !finalizing)
                            },
                        )
                        replaceItem(id) { it.copy(
                            status = ExtractionStatus.SAVED, outputUri = output.toString(), message = null
                        ) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        val hasOutput = session?.items?.firstOrNull { it.range.id == id }?.outputUri != null
                        runCatching {
                            replaceItem(id) { it.copy(
                                status = if (hasOutput) ExtractionStatus.BLOCKED else ExtractionStatus.FAILED,
                                reencodeNeeded = it.reencodeNeeded || error is ReencodeRequiredException,
                                message = if (hasOutput) "저장 상태가 불확실합니다. 복구 확인이 필요합니다."
                                    else if (error is ReencodeRequiredException)
                                        "고속 처리 실패 · 재인코딩 승인 후 재시도할 수 있습니다."
                                    else (error.localizedMessage ?: "저장 실패"),
                            ) }
                        }.onFailure { notice = "저장 결과 기록에 실패했습니다. 추가 저장을 중단합니다." }
                        if (hasOutput || blocked) break
                    }
                }
                notice = when {
                    blocked -> "일부 저장 결과를 확인하지 못했습니다. 복구 확인 후 재시도해 주세요."
                    session?.items?.any { it.status == ExtractionStatus.FAILED } == true ->
                        "일부 구간이 저장되지 않았습니다. 성공한 파일은 유지됩니다."
                    else -> "여러 구간을 각각 MP4로 저장했습니다."
                }
            } catch (cancelled: CancellationException) {
                notice = "저장을 중단했습니다. 완료된 파일은 유지됩니다. 남은 구간을 확인해 주세요."
            } catch (error: Exception) {
                notice = error.localizedMessage ?: "저장 중 오류가 발생했습니다."
            } finally {
                ExportWorkMonitor.finish(workId)
                exporting = false
                finalizing = false
                progressPercent = null
                job = null
                if (session?.items?.any {
                        it.status == ExtractionStatus.SAVING || it.status == ExtractionStatus.BLOCKED
                    } == true) recoverOutputs()
            }
        }
    }

    fun cancel() {
        if (!finalizing) job?.cancel()
    }

    fun recoverOutputs() {
        val current = session ?: return
        if (recovering || exporting || inspecting) return
        recovering = true
        viewModelScope.launch {
            try {
                val access = MediaStoreSingleExportAccess(app)
                val updated = withContext(Dispatchers.IO) {
                    current.items.map { item ->
                        val uri = item.outputUri
                        if (uri == null) {
                            if (item.status == ExtractionStatus.SAVING)
                                item.copy(status = ExtractionStatus.FAILED, message = "이전 저장 중단 — 다시 저장 가능")
                            else item
                        } else {
                            try {
                                val snapshot = SingleExportSnapshot(uri, item.range.durationMs, current.hasAudio)
                                when (access.inspect(snapshot)) {
                                    SingleExportOutputState.VERIFIED ->
                                        item.copy(status = ExtractionStatus.SAVED, message = null)
                                    SingleExportOutputState.OWNED_PENDING -> {
                                        access.removePending(snapshot)
                                        item.copy(status = ExtractionStatus.FAILED, outputUri = null,
                                            message = "미완성 출력 정리 완료 — 다시 저장 가능")
                                    }
                                    SingleExportOutputState.ABSENT ->
                                        item.copy(status = ExtractionStatus.FAILED, outputUri = null,
                                            message = "이전 출력이 없어 다시 저장할 수 있습니다.")
                                    SingleExportOutputState.UNCERTAIN ->
                                        item.copy(status = ExtractionStatus.BLOCKED,
                                            message = "파일 소유권 또는 저장 상태를 확인할 수 없습니다.")
                                }
                            } catch (_: Exception) {
                                item.copy(status = ExtractionStatus.BLOCKED,
                                    message = "저장 상태를 확인할 수 없습니다. 다시 확인해 주세요.")
                            }
                        }
                    }
                }
                persist(current.copy(items = updated))
                if (blocked) notice = "확인되지 않은 결과가 있습니다. 중복 저장을 방지하기 위해 재시도를 제한합니다."
            } catch (_: Exception) {
                notice = "출력 복구 기록에 실패했습니다. 추가 저장을 중단합니다."
            } finally {
                recovering = false
            }
        }
    }

    private fun invalidateReview() {
        reviewedRanges = null
        reviewedFast = null
        requiresReencodeApproval = false
    }

    private fun replaceItem(id: Long, edit: (ExtractionItem) -> ExtractionItem) {
        val snapshot = session ?: error("저장할 구간 정보가 없습니다.")
        persist(snapshot.copy(items = snapshot.items.map { if (it.range.id == id) edit(it) else it }))
    }

    private fun persist(next: ExtractionSession) {
        store.save(next)
        session = next
    }
}
