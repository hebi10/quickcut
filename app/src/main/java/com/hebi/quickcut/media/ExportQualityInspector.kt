package com.hebi.quickcut.media

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.hebi.quickcut.editor.EditTimeline
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Read-only preflight. No original file is copied or modified. */
object ExportQualityInspector {
    suspend fun inspect(
        context: Context,
        timeline: EditTimeline,
        settings: ExportQualitySettings,
    ): QualityEvaluation = withContext(Dispatchers.IO) {
        if (settings.isAutomatic) return@withContext QualityEvaluation()
        if (timeline.isEmpty) return@withContext QualityEvaluation(error = "편집할 영상이 없습니다.")
        val sources = try {
            timeline.clips.map { clip ->
                val source = readSource(context, Uri.parse(clip.uri))
                clip.crop?.let { region ->
                    source.copy(
                        width = (source.width * region.width).roundToInt().coerceAtLeast(16),
                        height = (source.height * region.height).roundToInt().coerceAtLeast(16),
                    )
                } ?: source
            }
        } catch (_: Exception) {
            return@withContext QualityEvaluation(error = "원본 영상의 해상도·FPS를 읽을 수 없습니다.")
        }
        val evaluation = ExportQualityPolicy.evaluate(sources, timeline.totalDurationMs, settings)
        val plan = evaluation.plan ?: return@withContext evaluation
        val rate = settings.frameRate.frames?.toDouble() ?: sources.mapNotNull { it.frameRate }
            .minOrNull()?.toDouble() ?: 30.0
        if (!canEncodeAvc(plan.width, plan.height, rate)) {
            return@withContext QualityEvaluation(
                error = "현재 기기에서 이 해상도·FPS 조합을 인코딩할 수 없습니다. 다른 설정을 선택해 주세요."
            )
        }
        evaluation
    }

    fun verifyEncodedFile(file: File, plan: ResolvedExportQuality) {
        val retriever = MediaMetadataRetriever()
        val shortSide = try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: throw IOException("출력 영상의 가로 크기를 확인하지 못했습니다.")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: throw IOException("출력 영상의 세로 크기를 확인하지 못했습니다.")
            minOf(width, height)
        } finally {
            retriever.release()
        }
        if (abs(shortSide - plan.shortSide) > 16) {
            throw IOException("요청한 출력 해상도가 실제 영상과 다릅니다. 갤러리에 공개하지 않았습니다.")
        }
        if (plan.targetFps != null) {
            val actual = sampleFrameRate(file.absolutePath)
                ?: throw IOException("출력 프레임 속도를 검증할 수 없습니다. 갤러리에 공개하지 않았습니다.")
            if (abs(actual - plan.targetFps) > 2.0f) {
                throw IOException("요청한 FPS와 실제 출력 FPS가 달라 결과를 저장하지 않았습니다.")
            }
        }
    }

    private fun readSource(context: Context, uri: Uri): SourceVideoSpec {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: error("Source width missing")
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: error("Source height missing")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val (width, height) = if (rotation % 180 != 0) rawHeight to rawWidth
                else rawWidth to rawHeight
            val extractor = MediaExtractor()
            var fps: Float? = null
            var hdr = false
            try {
                extractor.setDataSource(context, uri, null)
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val type = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!type.startsWith("video/")) continue
                    fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                        runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                            .getOrNull()?.takeIf { it in 1f..240f } else null
                    if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                        val transfer = runCatching {
                            format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                        }.getOrNull()
                        hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                            transfer == MediaFormat.COLOR_TRANSFER_HLG
                    }
                    break
                }
            } finally {
                extractor.release()
            }
            if (fps == null) {
                fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.takeIf { it in 1f..240f }
            }
            return SourceVideoSpec(width, height, fps, hdr)
        } finally {
            retriever.release()
        }
    }

    private fun canEncodeAvc(width: Int, height: Int, fps: Double): Boolean =
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                if (!info.isEncoder || info.supportedTypes.none {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                }) return@any false
                runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .videoCapabilities.areSizeAndRateSupported(width, height, fps)
                }.getOrDefault(false)
            }
        }.getOrDefault(false)

    /** Inspect presentation timestamps, not just an often-missing MP4 frame-rate metadata tag. */
    private fun sampleFrameRate(file: String): Float? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return null
            extractor.selectTrack(track)
            var first = -1L
            var last = -1L
            var count = 0
            while (count < 180) {
                val time = extractor.sampleTime
                if (time < 0L) break
                if (first < 0L) first = time
                last = time
                count++
                if (!extractor.advance()) break
            }
            if (count < 4 || last <= first) return null
            return ((count - 1) * 1_000_000.0 / (last - first)).toFloat()
        } finally {
            extractor.release()
        }
    }
}
