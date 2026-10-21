package com.hebi.quickcut.batch

import com.hebi.quickcut.editor.TrimInput

internal data class BatchTimeParts(val minutes: String, val seconds: String)

/** Existing drafts use total seconds; the editor also accepts explicit minutes:seconds. */
internal object BatchTimeInput {
    private const val MAX_MS = 7_200_000L
    private val wholeNumber = Regex("[0-9]{1,3}")
    private val decimal = Regex("([0-9]+([.,][0-9]{0,3})?|[.,][0-9]{1,3})")

    fun parse(text: String): Long? {
        val value = text.trim()
        if (':' !in value) return secondsMillis(value)?.takeIf { it <= MAX_MS }
        val parts = parts(value)
        if (error(parts) != null) return null
        return (parts.minutes.trim().toLongOrNull() ?: 0L) * 60_000L +
            (secondsMillis(parts.seconds.trim()) ?: 0L)
    }

    fun parts(text: String): BatchTimeParts {
        if (':' in text) return BatchTimeParts(text.substringBefore(':'), text.substringAfter(':'))
        val millis = parse(text) ?: return BatchTimeParts("0", text)
        return BatchTimeParts((millis / 60_000L).toString(),
            TrimInput.secondsText(millis % 60_000L))
    }

    fun error(parts: BatchTimeParts): String? {
        val minutes = parts.minutes.trim()
        val seconds = parts.seconds.trim()
        if (minutes.isEmpty() && seconds.isEmpty()) return "시간을 입력해 주세요."
        if (minutes.isNotEmpty() && (!wholeNumber.matches(minutes) || minutes.toLong() > 120L)) {
            return "분은 0~120의 정수로 입력해 주세요."
        }
        val secondsMs = if (seconds.isEmpty()) 0L else secondsMillis(seconds)
        if (secondsMs == null || secondsMs >= 60_000L) return "초는 0~59.999로 입력해 주세요."
        if ((minutes.toLongOrNull() ?: 0L) * 60_000L + secondsMs > MAX_MS) {
            return "한 구간은 최대 120분까지 설정할 수 있습니다."
        }
        return null
    }

    fun durationText(millis: Long): String {
        val minutes = millis / 60_000L
        val seconds = TrimInput.secondsText(millis % 60_000L)
        return when {
            minutes == 0L -> "${seconds}초"
            else -> "${minutes}분 ${seconds}초"
        }
    }

    fun summary(template: BatchTemplate): String {
        val leading = durationText(template.leadingMs)
        val trailing = durationText(template.trailingMs)
        return when (template.mode) {
            BatchClipMode.REMOVE_EDGES -> "각 영상의 앞 ${leading}와 뒤 ${trailing}를 삭제합니다."
            BatchClipMode.KEEP_FIRST -> "각 영상의 처음 ${leading}만 남깁니다."
            BatchClipMode.KEEP_LAST -> "각 영상의 마지막 ${trailing}만 남깁니다."
            BatchClipMode.JOIN_EDGES -> "각 영상의 처음 ${leading}와 마지막 ${trailing}를 이어 붙이고 중간은 삭제합니다."
        }
    }

    private fun secondsMillis(text: String): Long? {
        if (text.length > 16 || !decimal.matches(text)) return null
        return TrimInput.millisValue(text)
    }
}
