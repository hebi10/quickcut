package com.hebi.quickcut.segments

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.TrimInput
import com.hebi.quickcut.editor.VideoClip

data class ExtractionRange(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

object ExtractionPlanner {
    const val MAX_SEGMENTS = 30

    fun isValid(range: ExtractionRange, sourceDurationMs: Long): Boolean =
        sourceDurationMs >= TrimInput.MIN_DURATION_MS &&
            range.startMs >= 0L && range.endMs <= sourceDurationMs &&
            range.durationMs >= TrimInput.MIN_DURATION_MS

    fun initial(sourceDurationMs: Long): List<ExtractionRange> {
        require(sourceDurationMs >= TrimInput.MIN_DURATION_MS)
        return listOf(ExtractionRange(1L, 0L, minOf(sourceDurationMs, 4_000L)))
    }

    fun add(
        previous: List<ExtractionRange>,
        sourceDurationMs: Long,
        atMs: Long,
    ): List<ExtractionRange>? {
        if (previous.size >= MAX_SEGMENTS || sourceDurationMs < TrimInput.MIN_DURATION_MS) return null
        val start = atMs.coerceIn(0L, sourceDurationMs - TrimInput.MIN_DURATION_MS)
        val end = (start + 4_000L).coerceAtMost(sourceDurationMs)
        if (previous.any { it.startMs == start && it.endMs == end }) return null
        val nextId = (previous.maxOfOrNull { it.id } ?: 0L) + 1L
        return previous + ExtractionRange(nextId, start, end)
    }

    fun replace(
        previous: List<ExtractionRange>,
        id: Long,
        startMs: Long,
        endMs: Long,
        sourceDurationMs: Long,
    ): List<ExtractionRange>? {
        val next = ExtractionRange(id, startMs, endMs)
        if (!isValid(next, sourceDurationMs) ||
            previous.none { it.id == id } ||
            previous.any { it.id != id && it.startMs == startMs && it.endMs == endMs }
        ) return null
        return previous.map { if (it.id == id) next else it }
    }

    fun timeline(source: String, sourceDurationMs: Long, hasAudio: Boolean, range: ExtractionRange): EditTimeline {
        require(source.isNotBlank() && isValid(range, sourceDurationMs))
        return EditTimeline(listOf(VideoClip(range.id, source, sourceDurationMs,
            range.startMs, range.endMs, muted = false, hasAudio = hasAudio)))
    }

    fun outputName(originalName: String, ordinal: Int): String {
        require(ordinal > 0)
        val base = originalName.substringBeforeLast('.')
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_', '.', ' ').take(30)
            .ifEmpty { "QuickCut" }
        return "${base}_구간${ordinal.toString().padStart(2, '0')}"
    }
}
