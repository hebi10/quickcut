package com.hebi.quickcut.editor

import kotlin.math.max
import kotlin.math.min

/** One layer uses clip-local times. Positions are normalized relative to the visible video frame. */
data class TextLayer(
    val id: Long,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val x: Float = 0.5f,
    val y: Float = 0.78f,
    val size: Int = 100,
    val color: TextLayerColor = TextLayerColor.WHITE,
    val alignment: TextLayerAlignment = TextLayerAlignment.CENTER,
    val darkBackground: Boolean = false,
) {
    val durationMs: Long get() = endMs - startMs

    fun visibleAt(positionMs: Long): Boolean =
        positionMs >= startMs && positionMs < endMs && text.isNotBlank()

    fun moved(dx: Float, dy: Float): TextLayer = copy(
        x = (x + dx).coerceIn(TextLayerPolicy.MIN_ANCHOR, TextLayerPolicy.MAX_ANCHOR),
        y = (y + dy).coerceIn(TextLayerPolicy.MIN_ANCHOR, TextLayerPolicy.MAX_ANCHOR),
    )
}

enum class TextLayerColor(val label: String, val argb: Int) {
    WHITE("흰색", 0xFFFFFFFF.toInt()),
    BLACK("검정", 0xFF101014.toInt()),
    PURPLE("보라", 0xFFB79CFF.toInt()),
    YELLOW("노랑", 0xFFFFDE72.toInt()),
}

enum class TextLayerAlignment(val label: String) {
    START("왼쪽"), CENTER("가운데"), END("오른쪽"),
}

object TextLayerPolicy {
    const val MAX_LAYERS = 5
    const val MAX_CHARS = 80
    const val MAX_LINES = 3
    const val MIN_DURATION_MS = 100L
    const val MIN_ANCHOR = 0.14f
    const val MAX_ANCHOR = 0.86f

    fun isValid(layer: TextLayer, clipDurationMs: Long): Boolean {
        val actualLines = layer.text.split('\n')
        return clipDurationMs >= MIN_DURATION_MS &&
            layer.id > 0 &&
            layer.text.isNotBlank() && layer.text.length <= MAX_CHARS &&
            actualLines.size <= MAX_LINES && actualLines.all { it.length <= 32 } &&
            layer.startMs >= 0L && layer.endMs <= clipDurationMs &&
            layer.durationMs >= MIN_DURATION_MS &&
            layer.x.isFinite() && layer.y.isFinite() &&
            layer.x in MIN_ANCHOR..MAX_ANCHOR && layer.y in MIN_ANCHOR..MAX_ANCHOR &&
            layer.size in 60..170
    }

    fun validate(layers: List<TextLayer>, clipDurationMs: Long) {
        require(layers.size <= MAX_LAYERS) { "텍스트는 한 클립에 최대 5개까지 추가할 수 있습니다." }
        require(layers.map { it.id }.distinct().size == layers.size) { "텍스트 ID가 중복됐습니다." }
        require(layers.all { isValid(it, clipDurationMs) }) { "텍스트·위치·표시 시간을 확인해 주세요." }
    }

    /** Preserve source-timeline positions when the clip is trimmed or split. */
    fun reframe(
        layers: List<TextLayer>,
        removedStartMs: Long,
        newClipDurationMs: Long,
    ): List<TextLayer> {
        if (newClipDurationMs < MIN_DURATION_MS) return emptyList()
        return layers.mapNotNull { layer ->
            val start = (layer.startMs - removedStartMs).coerceIn(0L, newClipDurationMs)
            val end = (layer.endMs - removedStartMs).coerceIn(0L, newClipDurationMs)
            if (end - start < MIN_DURATION_MS) null
            else layer.copy(startMs = start, endMs = end)
        }
    }

    fun newLayer(existing: List<TextLayer>, durationMs: Long, atMs: Long): TextLayer {
        require(existing.size < MAX_LAYERS && durationMs >= MIN_DURATION_MS)
        val start = atMs.coerceIn(0L, durationMs - MIN_DURATION_MS)
        val end = (start + 3_000L).coerceAtMost(durationMs)
        return TextLayer(
            id = (existing.maxOfOrNull { it.id } ?: 0L) + 1L,
            text = "",
            startMs = start,
            endMs = end,
        )
    }
}
