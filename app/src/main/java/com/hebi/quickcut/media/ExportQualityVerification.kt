package com.hebi.quickcut.media

import java.io.IOException
import kotlin.math.abs

/** Validation is independent of the device codec: unverified output is never published. */
internal object ExportQualityVerification {
    private const val MAX_SIZE_DIFFERENCE_PX = 16L
    private const val MAX_ASPECT_RATIO_DIFFERENCE = 0.02
    private const val MAX_FPS_DIFFERENCE = 2f

    fun requireGeometry(
        plan: ResolvedExportQuality,
        encodedWidth: Int,
        encodedHeight: Int,
        rotationDegrees: Int,
    ) {
        if (plan.width <= 0 || plan.height <= 0 || encodedWidth <= 0 || encodedHeight <= 0) {
            throw IOException("출력 영상의 크기를 확인할 수 없습니다. 갤러리에 공개하지 않았습니다.")
        }
        val rotation = ((rotationDegrees % 360) + 360) % 360
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw IOException("출력 영상의 회전 정보를 확인할 수 없습니다. 갤러리에 공개하지 않았습니다.")
        }
        val width = if (rotation == 90 || rotation == 270) encodedHeight else encodedWidth
        val height = if (rotation == 90 || rotation == 270) encodedWidth else encodedHeight
        val actualRatio = width.toDouble() / height
        val expectedRatio = plan.width.toDouble() / plan.height

        if (abs(width.toLong() - plan.width.toLong()) > MAX_SIZE_DIFFERENCE_PX ||
            abs(height.toLong() - plan.height.toLong()) > MAX_SIZE_DIFFERENCE_PX ||
            abs(actualRatio - expectedRatio) > MAX_ASPECT_RATIO_DIFFERENCE
        ) {
            throw IOException(
                "요청한 출력 해상도 또는 화면 비율이 실제 영상과 다릅니다. 갤러리에 공개하지 않았습니다.",
            )
        }
    }

    /**
     * Two different presentation timestamps suffice for short clips; a single frame
     * cannot establish an FPS. Use the full PTS span because decode order can differ.
     */
    fun frameRateFromTimestamps(timestampsUs: List<Long>): Float? {
        if (timestampsUs.size < 2 || timestampsUs.any { it < 0L }) return null
        if (timestampsUs.distinct().size != timestampsUs.size) return null
        val span = timestampsUs.maxOrNull()!! - timestampsUs.minOrNull()!!
        if (span <= 0L) return null
        val measured = (timestampsUs.size - 1) * 1_000_000.0 / span.toDouble()
        return measured.takeIf { it.isFinite() && it > 0.0 }?.toFloat()
    }

    fun requireFrameRate(expectedFps: Int, actualFps: Float?) {
        if (actualFps == null || !actualFps.isFinite()) {
            throw IOException("출력 프레임 속도를 검증할 수 없습니다. 갤러리에 공개하지 않았습니다.")
        }
        if (abs(actualFps - expectedFps) > MAX_FPS_DIFFERENCE) {
            throw IOException("요청한 FPS와 실제 출력 FPS가 달라 결과를 저장하지 않았습니다.")
        }
    }
}
