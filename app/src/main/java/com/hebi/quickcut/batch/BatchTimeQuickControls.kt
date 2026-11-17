package com.hebi.quickcut.batch

import com.hebi.quickcut.editor.TrimInput

internal data class BatchTimeValues(val leading: String, val trailing: String)

internal object BatchTimeQuickControls {
    val presets = listOf(1, 3, 5, 10)
    private const val STEP_MS = 1_000L
    private const val MAX_MS = 7_200_000L

    fun preset(mode: BatchClipMode, leading: String, trailing: String, seconds: Int): BatchTimeValues {
        require(seconds in presets)
        val value = "0:" + seconds
        return BatchTimeValues(if (mode.usesLeading) value else leading,
            if (mode.usesTrailing) value else trailing)
    }

    fun selected(mode: BatchClipMode, leading: String, trailing: String, seconds: Int): Boolean {
        if (seconds !in presets) return false
        val millis = seconds * STEP_MS
        return (!mode.usesLeading || BatchTimeInput.parse(leading) == millis) &&
            (!mode.usesTrailing || BatchTimeInput.parse(trailing) == millis)
    }

    // Invalid manual inputs stay untouched; only explicit button presses change valid values.
    fun step(value: String, direction: Int): String? {
        require(direction == -1 || direction == 1)
        val millis = BatchTimeInput.parse(value) ?: return null
        val updated = (millis + direction * STEP_MS).coerceIn(0L, MAX_MS)
        if (updated == millis) return null
        val parts = BatchTimeInput.parts(TrimInput.secondsText(updated))
        return parts.minutes + ":" + parts.seconds
    }
}
