package com.hebi.quickcut.media

import kotlin.math.roundToInt

enum class ExportResolution(val shortSide: Int?, val label: String) {
    AUTO(null, "자동 · 원본 기준"),
    HD_720(720, "720p"),
    FULL_HD_1080(1080, "1080p"),
    ULTRA_HD_2160(2160, "4K"),
}

enum class ExportFrameRate(val frames: Int?, val label: String) {
    AUTO(null, "원본 유지"),
    FPS_30(30, "30 FPS"),
    FPS_60(60, "60 FPS"),
}

enum class ExportQualityLevel(val multiplier: Double, val label: String) {
    AUTO(1.0, "자동"),
    LOW(0.6, "낮음"),
    BALANCED(1.0, "보통"),
    HIGH(1.6, "높음"),
}

data class ExportQualitySettings(
    val resolution: ExportResolution = ExportResolution.AUTO,
    val frameRate: ExportFrameRate = ExportFrameRate.AUTO,
    val quality: ExportQualityLevel = ExportQualityLevel.AUTO,
) {
    val isAutomatic: Boolean
        get() = resolution == ExportResolution.AUTO &&
            frameRate == ExportFrameRate.AUTO && quality == ExportQualityLevel.AUTO
}

data class SourceVideoSpec(
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val hdr: Boolean = false,
) {
    val shortSide: Int get() = minOf(width, height)
    val longSide: Int get() = maxOf(width, height)
}

data class ResolvedExportQuality(
    val shortSide: Int,
    val width: Int,
    val height: Int,
    val targetFps: Int?,
    val bitrate: Int,
    val estimatedBytes: Long,
) {
    val detail: String get() =
        "${width}×${height} · ${targetFps?.let { "${it}fps" } ?: "원본 FPS 기준"}" +
            " · 약 ${"%.1f".format(estimatedBytes / 1_048_576.0)}MB"
}

data class QualityEvaluation(
    val plan: ResolvedExportQuality? = null,
    val error: String? = null,
) {
    val supported: Boolean get() = error == null
}

object ExportQualityPolicy {
    fun evaluate(
        sources: List<SourceVideoSpec>,
        durationMs: Long,
        quality: ExportQualitySettings,
    ): QualityEvaluation {
        if (quality.isAutomatic) return QualityEvaluation()
        if (durationMs < 100L || sources.isEmpty() ||
            sources.any { it.width < 16 || it.height < 16 }
        ) return QualityEvaluation(error = "원본 영상의 크기를 확인할 수 없습니다.")

        if (sources.any { it.hdr }) return QualityEvaluation(
            error = "HDR 영상은 원본 보존을 위해 아직 사용자 지정 품질 저장을 지원하지 않습니다."
        )
        val first = sources.first()
        val referenceRatio = first.longSide.toDouble() / first.shortSide
        if (sources.any {
                kotlin.math.abs(it.longSide.toDouble() / it.shortSide - referenceRatio) > 0.02
            }) return QualityEvaluation(error = "화면 비율이 다른 영상은 먼저 개별 저장해 주세요.")

        val shortSide = quality.resolution.shortSide
            ?: sources.minOf { it.shortSide }
        if (sources.any { it.shortSide < shortSide }) return QualityEvaluation(
            error = "원본보다 높은 해상도로 확대 저장할 수 없습니다."
        )
        val requestedFps = quality.frameRate.frames
        if (requestedFps != null && sources.any {
                it.frameRate == null || it.frameRate < requestedFps - 0.5f
            }) return QualityEvaluation(
                error = "원본 FPS가 낮거나 확인되지 않아 ${requestedFps}fps 저장을 지원하지 않습니다."
            )

        val longSide = ((shortSide * referenceRatio).roundToInt() + 1) / 2 * 2
        val portrait = first.height > first.width
        val width = if (portrait) shortSide else longSide
        val height = if (portrait) longSide else shortSide
        val fpsForBitrate = requestedFps?.toDouble() ?: sources.mapNotNull { it.frameRate }
            .minOrNull()?.toDouble() ?: 30.0
        val scaledBitrate = 8_000_000.0 * (width.toDouble() * height / (1920 * 1080)) *
            (fpsForBitrate / 30.0) * quality.quality.multiplier
        val bitrate = scaledBitrate.toLong().coerceIn(1_000_000L, 80_000_000L).toInt()
        val audioBits = 160_000L
        val estimatedBytes = ((bitrate.toLong() + audioBits) * durationMs / 8_000L * 1.2)
            .toLong().coerceAtLeast(1_048_576L)
        return QualityEvaluation(ResolvedExportQuality(
            shortSide = shortSide, width = width, height = height,
            targetFps = requestedFps, bitrate = bitrate, estimatedBytes = estimatedBytes,
        ))
    }
}
