package com.hebi.quickcut.editor

import java.math.BigDecimal
import java.math.RoundingMode

data class TrimRange(val startMs: Long, val endMs: Long)

/** Shared parsing for the quick removed edges and the detailed selected range. */
object TrimInput {
    const val MIN_DURATION_MS = 100L
    private val maxMillis = BigDecimal.valueOf(Long.MAX_VALUE)
    private val halfMillisecond = BigDecimal("0.5")

    fun millisValue(text: String): Long? {
        val seconds = text.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
        if (seconds.signum() < 0) return null
        val milliseconds = seconds.multiply(BigDecimal.valueOf(1_000L))
        if (milliseconds > maxMillis) return null
        if (milliseconds < halfMillisecond) return 0L
        return runCatching {
            milliseconds.setScale(0, RoundingMode.HALF_UP).longValueExact()
        }.getOrNull()
    }

    fun secondsText(valueMs: Long): String =
        BigDecimal.valueOf(valueMs, 3).stripTrailingZeros().toPlainString()

    fun removedRange(leading: String, trailing: String, sourceDurationMs: Long): TrimRange? {
        val head = millisValue(leading) ?: return null
        val tail = millisValue(trailing) ?: return null
        if (head > sourceDurationMs || tail > sourceDurationMs) return null
        return range(head, sourceDurationMs - tail, sourceDurationMs)
    }

    fun selectedRange(start: String, end: String, sourceDurationMs: Long): TrimRange? {
        val startMs = millisValue(start) ?: return null
        val endMs = millisValue(end) ?: return null
        return range(startMs, endMs, sourceDurationMs)
    }

    fun range(startMs: Long, endMs: Long, sourceDurationMs: Long): TrimRange? =
        if (sourceDurationMs > 0L && startMs >= 0L && endMs <= sourceDurationMs &&
            endMs >= startMs && endMs - startMs >= MIN_DURATION_MS)
            TrimRange(startMs, endMs) else null
}
