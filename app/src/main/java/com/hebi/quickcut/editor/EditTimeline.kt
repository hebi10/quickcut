package com.hebi.quickcut.editor

import java.util.Locale

private const val MIN_CLIP_MS = 100L

data class PreciseClipRange(val startUs: Long, val endUs: Long) {
    init { require(startUs >= 0L && endUs > startUs) }
}

internal fun ceilMilliseconds(timeUs: Long): Long =
    timeUs / 1_000L + if (timeUs % 1_000L == 0L) 0L else 1L

data class VideoClip(
    val id: Long,
    val uri: String,
    val sourceDurationMs: Long,
    val startMs: Long = 0L,
    val endMs: Long = sourceDurationMs,
    val muted: Boolean = false,
    val hasAudio: Boolean = true,
    val preciseRange: PreciseClipRange? = null,
) {
    init {
        require(sourceDurationMs > 0L) { "영상 길이는 0보다 커야 합니다." }
        require(startMs >= 0L && endMs <= sourceDurationMs && endMs > startMs) {
            "잘못된 영상 구간입니다."
        }
    }

    // A legacy copy(startMs/endMs) must never silently retain old exact boundaries.
    private val activeRange: PreciseClipRange?
        get() = preciseRange?.takeIf {
            it.startUs / 1_000L == startMs && ceilMilliseconds(it.endUs) == endMs
        }
    val startUs: Long get() = activeRange?.startUs ?: Math.multiplyExact(startMs, 1_000L)
    val endUs: Long get() = activeRange?.endUs ?: Math.multiplyExact(endMs, 1_000L)
    val durationUs: Long get() = endUs - startUs
    val durationMs: Long get() = activeRange?.let { ceilMilliseconds(it.endUs - it.startUs) } ?: (endMs - startMs)

    fun withRangeUs(startUs: Long, endUs: Long): VideoClip {
        require(startUs >= 0L && endUs > startUs && ceilMilliseconds(endUs) <= sourceDurationMs)
        return copy(startMs = startUs / 1_000L, endMs = ceilMilliseconds(endUs),
            preciseRange = if (startUs % 1_000L == 0L && endUs % 1_000L == 0L) null
                else PreciseClipRange(startUs, endUs))
    }
}

data class TimelinePosition(val index: Int, val offsetMs: Long)

data class EditTimeline(val clips: List<VideoClip> = emptyList()) {
    val totalDurationUs: Long get() = clips.fold(0L) { total, clip -> Math.addExact(total, clip.durationUs) }
    val totalDurationMs: Long get() = if (clips.any { it.preciseRange != null })
        ceilMilliseconds(totalDurationUs) else clips.sumOf { it.durationMs }
    val isEmpty: Boolean get() = clips.isEmpty()

    fun append(items: List<VideoClip>): EditTimeline = copy(clips = clips + items)

    fun updateRange(id: Long, startMs: Long, endMs: Long): EditTimeline {
        val clip = clips.first { it.id == id }
        require(startMs >= 0 && endMs <= clip.sourceDurationMs && endMs - startMs >= MIN_CLIP_MS)
        return copy(clips = clips.map {
            if (it.id == id) it.copy(startMs = startMs, endMs = endMs, preciseRange = null) else it
        })
    }

    fun split(id: Long, offsetMs: Long, newId: Long): EditTimeline {
        require(clips.none { it.id == newId }) { "중복 클립 ID입니다." }
        val index = clips.indexOfFirst { it.id == id }
        require(index >= 0) { "클립을 찾지 못했습니다." }
        val clip = clips[index]
        val offsetUs = Math.multiplyExact(offsetMs, 1_000L)
        require(offsetMs >= MIN_CLIP_MS && clip.durationUs - offsetUs >= MIN_CLIP_MS * 1_000L) {
            "분할 위치가 클립 경계에 너무 가깝습니다."
        }
        val splitUs = clip.startUs + offsetUs
        val left = clip.withRangeUs(clip.startUs, splitUs)
        val right = clip.withRangeUs(splitUs, clip.endUs).copy(id = newId)
        return copy(clips = clips.toMutableList().apply {
            removeAt(index)
            add(index, left)
            add(index + 1, right)
        })
    }

    fun delete(id: Long): EditTimeline = copy(clips = clips.filterNot { it.id == id })

    fun move(id: Long, delta: Int): EditTimeline {
        val from = clips.indexOfFirst { it.id == id }
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, clips.lastIndex)
        if (to == from) return this
        return copy(clips = clips.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun toggleMute(id: Long): EditTimeline =
        copy(clips = clips.map { if (it.id == id) it.copy(muted = !it.muted) else it })

    fun startOf(index: Int): Long = clips.take(index).sumOf { it.durationMs }

    fun positionFor(globalPositionMs: Long): TimelinePosition {
        if (clips.isEmpty()) return TimelinePosition(0, 0)
        var remaining = globalPositionMs.coerceIn(0, totalDurationMs)
        for ((index, clip) in clips.withIndex()) {
            if (remaining < clip.durationMs || index == clips.lastIndex) {
                return TimelinePosition(index, remaining.coerceAtMost(clip.durationMs))
            }
            remaining -= clip.durationMs
        }
        return TimelinePosition(0, 0)
    }

    fun clipAt(id: Long): VideoClip? = clips.firstOrNull { it.id == id }
}

fun formatTime(timeMs: Long): String {
    val deciseconds = timeMs.coerceAtLeast(0L) / 100L
    val seconds = deciseconds / 10
    val tenth = deciseconds % 10
    val minutes = (seconds / 60) % 60
    val hours = seconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d.%d", hours, minutes, seconds % 60, tenth)
    } else {
        String.format(Locale.US, "%02d:%02d.%d", minutes, seconds % 60, tenth)
    }
}
