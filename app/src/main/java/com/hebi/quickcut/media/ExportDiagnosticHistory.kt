package com.hebi.quickcut.media

import java.util.UUID

internal enum class ExportDiagnosticStatus(val label: String) {
    RUNNING("처리 중"), COMPLETE("완료"), CANCELLED("취소"), FAILED("실패")
}

/** Deliberately accepts no source identifier, output name, throwable, or free-form error. */
internal data class ExportDiagnosticRecord(
    val operationId: String,
    val status: ExportDiagnosticStatus,
    val stage: ExportStage,
    val elapsedMs: Long,
    val stageMs: Map<ExportStage, Long>,
    val facts: ExportDiagnosticFacts,
    val reasons: ExportDiagnostics,
    val method: String?,
    val failureType: String?,
) {
    val text: String get() = facts.text(stage, status.label, elapsedMs, reasons, method, failureType,
        operationId, stageMs)

    fun isValid(): Boolean = runCatching {
        UUID.fromString(operationId).toString() == operationId && elapsedMs >= 0 &&
            stageMs.values.all { it >= 0 && it <= elapsedMs } &&
            stageMs.values.fold(0L) { sum, value -> Math.addExact(sum, value) } == elapsedMs &&
            (failureType == null || failureType in FAILURE_TYPES) &&
            (method == null || method in METHODS) && facts.isValid()
    }.getOrDefault(false)

    companion object {
        val FAILURE_TYPES = setOf("ReencodeRequiredException", "IOException", "SecurityException",
            "IllegalArgumentException", "IllegalStateException", "CancellationException", "Exception")
        private val METHODS = setOf("ORIGINAL_COPY", "PARTIAL_REENCODE", "AUDIO_REENCODE", "VIDEO_REENCODE", "UNKNOWN")
    }
}

/** Newest first; failure retention has a separate budget and survives successful later work. */
internal class ExportDiagnosticHistory private constructor(
    val recent: List<ExportDiagnosticRecord>,
    val failures: List<ExportDiagnosticRecord>,
) {
    constructor() : this(emptyList(), emptyList())

    fun record(operationId: String): ExportDiagnosticRecord? =
        recent.firstOrNull { it.operationId == operationId } ?: failures.firstOrNull { it.operationId == operationId }

    fun updated(record: ExportDiagnosticRecord): ExportDiagnosticHistory {
        if (!record.isValid()) return this
        return restore(listOf(record) + recent, if (record.status == ExportDiagnosticStatus.FAILED)
            listOf(record) + failures else failures)
    }

    companion object {
        const val RECENT_LIMIT = 12
        const val FAILURE_LIMIT = 6
        fun restore(recent: List<ExportDiagnosticRecord>, failures: List<ExportDiagnosticRecord>) =
            ExportDiagnosticHistory(
                recent.filter { it.isValid() }.distinctBy { it.operationId }.take(RECENT_LIMIT),
                failures.filter { it.isValid() && it.status == ExportDiagnosticStatus.FAILED }
                    .distinctBy { it.operationId }.take(FAILURE_LIMIT))
    }
}

internal data class ExportStageTimes(val elapsedMs: Long, val stageMs: Map<ExportStage, Long>)

/** The supplied clock must be monotonic. A finished snapshot is immutable and idempotent. */
internal class ExportStageTiming(private val clock: () -> Long) {
    private val startedAt = clock()
    private var lastAt = startedAt
    private var current = ExportStage.PREPARING
    private val accumulated = mutableMapOf<ExportStage, Long>()
    private var finished: ExportStageTimes? = null

    fun stage(value: ExportStage) {
        if (finished != null) return
        accumulate()
        current = value
    }

    fun snapshot(): ExportStageTimes {
        finished?.let { return it }
        accumulate()
        return ExportStageTimes((lastAt - startedAt).coerceAtLeast(0), accumulated.toMap())
    }

    fun finish(): ExportStageTimes = finished ?: snapshot().also { finished = it }

    private fun accumulate() {
        val now = clock().coerceAtLeast(lastAt)
        accumulated[current] = (accumulated[current] ?: 0) + (now - lastAt)
        lastAt = now
    }
}
