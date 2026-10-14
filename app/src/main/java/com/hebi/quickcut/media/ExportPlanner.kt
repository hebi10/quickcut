package com.hebi.quickcut.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ExportMode {
    FAST_ONLY,
    ALLOW_REENCODE,
}

data class ExportAssessment(
    val fastAvailable: Boolean,
    val explanation: String,
    val suggestedStartMs: Long? = null,
)

/** A preview, not a speed guarantee: runtime remuxing may still fail. */
object ExportPlanner {
    suspend fun analyze(
        context: Context,
        timeline: EditTimeline,
        dateMode: SaveDateMode,
    ): ExportAssessment = withContext(Dispatchers.IO) {
        val assessment = when {
            timeline.isEmpty -> ExportAssessment(false, "편집할 영상이 없습니다.")
            timeline.clips.size > 1 -> ExportAssessment(
                false, "여러 클립 연결 및 분할 결과에는 전체 영상 처리가 필요할 수 있습니다."
            )
            dateMode == SaveDateMode.ORIGINAL -> ExportAssessment(
                false, "현재 원본 날짜 유지 방식은 고속 복사를 지원하지 않습니다."
            )
            else -> {
                val clip = timeline.clips.single()
                if (FastMp4Trimmer.prepare(context, clip) != null) {
                    ExportAssessment(true, "현재 구간은 재인코딩 없이 빠르게 저장할 수 있습니다.")
                } else {
                    ExportAssessment(
                        fastAvailable = false,
                        explanation = "영상 코덱 또는 자르기 시작 위치 때문에 빠른 저장을 적용하기 어렵습니다.",
                        suggestedStartMs = nearbyKeyframe(context, clip),
                    )
                }
            }
        }
        if (timeline.isEmpty) return@withContext assessment
        val estimatedBytes = ExportStorage.estimateOutputBytes(context, timeline, assessment.fastAvailable)
        assessment.copy(
            explanation = assessment.explanation + "\n" +
                ExportStorage.explanation(context, estimatedBytes, assessment.fastAvailable),
        )
    }

    private fun nearbyKeyframe(context: Context, clip: VideoClip): Long? {
        if (clip.startMs <= 0L) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, Uri.parse(clip.uri), null)
            val video = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return null
            extractor.selectTrack(video)
            extractor.seekTo(clip.startMs * 1_000L, MediaExtractor.SEEK_TO_NEXT_SYNC)
            val candidateUs = extractor.sampleTime
            if (candidateUs < clip.startMs * 1_000L ||
                candidateUs > (clip.startMs + 10_000L) * 1_000L ||
                candidateUs >= (clip.endMs - 100L) * 1_000L
            ) return null
            val candidateMs = candidateUs / 1_000L
            val candidate = clip.copy(startMs = candidateMs)
            if (FastMp4Trimmer.prepare(context, candidate) != null &&
                candidateMs != clip.startMs
            ) candidateMs else null
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}

