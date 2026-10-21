package com.hebi.quickcut.ui

import com.hebi.quickcut.media.FastCutSuggestion
import java.util.Locale

internal fun fastCutSuggestionText(suggestion: FastCutSuggestion): String = buildString {
    suggestion.requested.clips.zip(suggestion.adjusted.clips).forEachIndexed { index, (before, after) ->
        if (before.startUs != after.startUs) {
            append("구간 ${index + 1} 시작\n요청 ${preciseTime(before.startUs)} → 추천 ${preciseTime(after.startUs)}\n")
            append("앞에서 ${preciseSeconds(after.startUs - before.startUs)}초 추가 삭제\n\n")
        }
        if (before.endUs != after.endUs) {
            append("구간 ${index + 1} 끝\n요청 ${preciseTime(before.endUs)} → 추천 ${preciseTime(after.endUs)}\n")
            append("뒤에서 ${preciseSeconds(before.endUs - after.endUs)}초 추가 삭제\n\n")
        }
    }
    append("총 ${preciseSeconds(suggestion.extraRemovedUs)}초가 추가로 삭제됩니다.")
}

internal fun preciseSeconds(us: Long): String =
    String.format(Locale.US, "%.6f", us / 1_000_000.0).trimEnd('0').trimEnd('.')

internal fun preciseTime(us: Long): String {
    val fractional = if (us % 1_000L == 0L) String.format(Locale.US, "%03d", us % 1_000_000 / 1_000)
        else String.format(Locale.US, "%06d", us % 1_000_000)
    return String.format(Locale.US, "%02d:%02d:%02d.%s", us / 3_600_000_000L,
        (us / 60_000_000L) % 60, (us / 1_000_000L) % 60, fractional)
}
