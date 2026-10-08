package com.hebi.quickcut.editor

import java.util.Locale

private const val MIN_CLIP_MS = 100L

data class VideoClip(
    val id: Long,
    val uri: String,
    val sourceDurationMs: Long,
    val startMs: Long = 0L,
    val endMs: Long = sourceDurationMs,
    val muted: Boolean = false,
    val hasAudio: Boolean = true,
) {
    init {
        require(sourceDurationMs > 0L) { "영상 길이는 0보다 커야 합니다." }
        require(startMs >= 0L && endMs <= sourceDurationMs && endMs > startMs) {
            "잘못된 영상 구간입니다."
        }
    }

    val durationMs: Long get() = endMs - startMs
}

data class TimelinePosition(val index: Int, val offsetMs: Long)

data class EditTimeline(val clips: List<VideoClip> = emptyList()) {
    val totalDurationMs: Long get() = clips.sumOf { it.durationMs }
    val isEmpty: Boolean get() = clips.isEmpty()

    fun append(items: List<VideoClip>): EditTimeline = copy(clips = clips + items)

    fun updateRange(id: Long, startMs: Long, endMs: Long): EditTimeline {
        val clip = clips.first { it.id == id }
        require(startMs >= 0 && endMs <= clip.sourceDurationMs && endMs - startMs >= MIN_CLIP_MS)
        return copy(clips = clips.map {
            if (it.id == id) it.copy(startMs = startMs, endMs = endMs) else it
        })
    }

    fun split(id: Long, offsetMs: Long, newId: Long): EditTimeline {
        require(clips.none { it.id == newId }) { "중복 클립 ID입니다." }
        val index = clips.indexOfFirst { it.id == id }
        require(index >= 0) { "클립을 찾지 못했습니다." }
        val clip = clips[index]
        require(offsetMs >= MIN_CLIP_MS && clip.durationMs - offsetMs >= MIN_CLIP_MS) {
            "분할 위치가 클립 경계에 너무 가깝습니다."
        }
        val splitMs = clip.startMs + offsetMs
        val left = clip.copy(endMs = splitMs)
        val right = clip.copy(id = newId, startMs = splitMs)
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
