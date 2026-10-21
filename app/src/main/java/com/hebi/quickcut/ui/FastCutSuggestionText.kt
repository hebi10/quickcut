package com.hebi.quickcut.ui

import com.hebi.quickcut.editor.TrimInput
import com.hebi.quickcut.media.FastCutSuggestion
import java.util.Locale

internal fun fastCutSuggestionText(suggestion: FastCutSuggestion): String = buildString {
    fun time(ms: Long) = String.format(Locale.US, "%02d:%02d:%02d.%03d",
        ms / 3_600_000, (ms / 60_000) % 60, (ms / 1_000) % 60, ms % 1_000)
    suggestion.requested.clips.zip(suggestion.adjusted.clips).forEachIndexed { index, (before, after) ->
        if (before.startMs != after.startMs) {
            append("구간 ${index + 1} 시작\n요청 ${time(before.startMs)} → 추천 ${time(after.startMs)}\n")
            append("앞에서 ${TrimInput.secondsText(after.startMs - before.startMs)}초 추가 삭제\n\n")
        }
        if (before.endMs != after.endMs) {
            append("구간 ${index + 1} 끝\n요청 ${time(before.endMs)} → 추천 ${time(after.endMs)}\n")
            append("뒤에서 ${TrimInput.secondsText(before.endMs - after.endMs)}초 추가 삭제\n\n")
        }
    }
    append("총 ${TrimInput.secondsText(suggestion.extraRemovedMs)}초가 추가로 삭제됩니다.")
}
