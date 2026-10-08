package com.hebi.quickcut.batch

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import java.util.UUID

/** Which duration inputs actually apply to each batch edit operation. */
enum class BatchClipMode(val usesLeading: Boolean, val usesTrailing: Boolean) {
    REMOVE_EDGES(true, true),
    KEEP_FIRST(true, false),
    KEEP_LAST(false, true),
    JOIN_EDGES(true, true),
}

data class BatchTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "앞뒤 3초 삭제",
    val mode: BatchClipMode = BatchClipMode.REMOVE_EDGES,
    val leadingMs: Long = 3_000L,
    val trailingMs: Long = 3_000L,
    val muteAudio: Boolean = false,
) {
    fun checkValidity(): String? {
        if (name.isBlank() || name.length > 40) return "템플릿 이름은 1~40자로 입력해 주세요."
        if (leadingMs !in 0..7_200_000L || trailingMs !in 0..7_200_000L) {
            return "편집 시간은 0~7,200초로 입력해 주세요."
        }
        return when (mode) {
            BatchClipMode.REMOVE_EDGES ->
                if (leadingMs == 0L && trailingMs == 0L) "삭제할 구간을 설정해 주세요." else null
            BatchClipMode.KEEP_FIRST ->
                if (leadingMs < 100L) "앞부분 추출은 최소 0.1초 이상이어야 합니다." else null
            BatchClipMode.KEEP_LAST ->
                if (trailingMs < 100L) "뒷부분 추출은 최소 0.1초 이상이어야 합니다." else null
            BatchClipMode.JOIN_EDGES ->
                if (leadingMs < 100L || trailingMs < 100L)
                    "앞뒤 연결은 두 구간 모두 최소 0.1초 이상이어야 합니다." else null
        }
    }

    fun plan(sourceUri: String, durationMs: Long, hasAudio: Boolean): BatchPlan {
        val invalid = checkValidity()
        if (invalid != null) return BatchPlan.Skip(invalid)
        if (durationMs < 100L) return BatchPlan.Skip("원본 영상이 너무 짧습니다.")
        val muted = muteAudio || !hasAudio

        fun clip(id: Long, start: Long, end: Long): VideoClip =
            VideoClip(id, sourceUri, durationMs, start, end, muted, hasAudio)

        return when (mode) {
            BatchClipMode.REMOVE_EDGES -> {
                if (durationMs - leadingMs - trailingMs < 100L) {
                    BatchPlan.Skip("앞뒤 구간을 제거할 만큼 영상이 길지 않습니다.")
                } else {
                    BatchPlan.Ready(EditTimeline(listOf(clip(1, leadingMs, durationMs - trailingMs))))
                }
            }
            BatchClipMode.KEEP_FIRST -> {
                if (durationMs < leadingMs) BatchPlan.Skip("원본이 추출할 앞 구간보다 짧습니다.")
                else BatchPlan.Ready(EditTimeline(listOf(clip(1, 0L, leadingMs))))
            }
            BatchClipMode.KEEP_LAST -> {
                if (durationMs < trailingMs) BatchPlan.Skip("원본이 추출할 뒷 구간보다 짧습니다.")
                else BatchPlan.Ready(
                    EditTimeline(listOf(clip(1, durationMs - trailingMs, durationMs)))
                )
            }
            BatchClipMode.JOIN_EDGES -> {
                if (durationMs - leadingMs - trailingMs < 100L) {
                    BatchPlan.Skip("중간에 제거할 구간이 부족합니다.")
                } else {
                    BatchPlan.Ready(EditTimeline(listOf(
                        clip(1, 0L, leadingMs),
                        clip(2, durationMs - trailingMs, durationMs),
                    )))
                }
            }
        }
    }
}

sealed interface BatchPlan {
    data class Ready(val timeline: EditTimeline) : BatchPlan
    data class Skip(val reason: String) : BatchPlan
}
