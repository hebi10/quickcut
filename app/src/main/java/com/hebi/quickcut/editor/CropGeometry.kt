package com.hebi.quickcut.editor

import kotlin.math.abs
import kotlin.math.min

/** Visible source-frame coordinates, normalized to [0,1] from top left. */
data class CropRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() })
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f)
        require(right - left >= MIN_FRACTION && bottom - top >= MIN_FRACTION)
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isWholeFrame: Boolean get() =
        abs(left) < 0.0001f && abs(top) < 0.0001f &&
            abs(right - 1f) < 0.0001f && abs(bottom - 1f) < 0.0001f

    fun move(dx: Float, dy: Float): CropRegion {
        val x = (left + dx).coerceIn(0f, 1f - width)
        val y = (top + dy).coerceIn(0f, 1f - height)
        return CropRegion(x, y, x + width, y + height)
    }

    /** scale > 1 means zooming further into the source frame. */
    fun zoom(scale: Float): CropRegion {
        if (!scale.isFinite() || scale <= 0f) return this
        val factor = (1f / scale).coerceIn(0.05f, 20f)
        val limited = min(factor, min(1f / width, 1f / height))
        val newW = (width * limited).coerceAtLeast(MIN_FRACTION).coerceAtMost(1f)
        val newH = (height * limited).coerceAtLeast(MIN_FRACTION).coerceAtMost(1f)
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val x = (cx - newW / 2f).coerceIn(0f, 1f - newW)
        val y = (cy - newH / 2f).coerceIn(0f, 1f - newH)
        return CropRegion(x, y, x + newW, y + newH)
    }

    /** Expand or shrink a freeform frame while keeping the top-left anchor. */
    fun resize(widthFraction: Float, heightFraction: Float): CropRegion {
        val w = widthFraction.coerceIn(MIN_FRACTION, 1f)
        val h = heightFraction.coerceIn(MIN_FRACTION, 1f)
        return CropRegion(left.coerceAtMost(1f - w), top.coerceAtMost(1f - h),
            left.coerceAtMost(1f - w) + w, top.coerceAtMost(1f - h) + h)
    }

    companion object {
        private const val MIN_FRACTION = 0.02f
        val FULL = CropRegion(0f, 0f, 1f, 1f)
    }
}

enum class CropRatio(val label: String, val aspect: Float?) {
    ORIGINAL("원본", null),
    VERTICAL("9:16", 9f / 16f),
    SQUARE("1:1", 1f),
    HORIZONTAL("16:9", 16f / 9f),
    FREE("자유", null),
}

object CropGeometry {
    fun centerForRatio(width: Int, height: Int, ratio: CropRatio): CropRegion {
        require(width > 0 && height > 0)
        val desired = ratio.aspect ?: return CropRegion.FULL
        val sourceAspect = width.toFloat() / height
        return if (desired < sourceAspect) {
            val fraction = desired / sourceAspect
            val x = (1f - fraction) / 2f
            CropRegion(x, 0f, x + fraction, 1f)
        } else {
            val fraction = sourceAspect / desired
            val y = (1f - fraction) / 2f
            CropRegion(0f, y, 1f, y + fraction)
        }
    }

    /** Convert a top-left image rect into OpenGL NDC (bottom left is -1,-1). */
    fun ndc(region: CropRegion): FloatArray = floatArrayOf(
        2f * region.left - 1f,
        2f * region.right - 1f,
        1f - 2f * region.bottom,
        1f - 2f * region.top,
    )
}
