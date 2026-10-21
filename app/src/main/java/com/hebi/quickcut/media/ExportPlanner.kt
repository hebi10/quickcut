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
    /** Copy video only; the native path may convert audio after review. */
    FAST_ONLY,
    ALLOW_REENCODE,
}

data class ExportAssessment(
    val fastAvailable: Boolean,
    val explanation: String,
    val suggestion: FastCutSuggestion? = null,
    val directCopy: Boolean = false,
) {
    val suggestedStartMs: Long? get() {
        val review = suggestion ?: return null
        val clip = review.adjusted.clips.singleOrNull() ?: return null
        return clip.startMs.takeIf { it > review.requested.clips.single().startMs }
    }
}

/** A preview, not a speed guarantee: runtime remuxing may still fail. */
object ExportPlanner {
    suspend fun analyze(
        context: Context,
        timeline: EditTimeline,
        dateMode: SaveDateMode,
        onProgress: (Int?) -> Unit = {},
    ): ExportAssessment = withContext(Dispatchers.IO) {
        var assessment = when {
            timeline.isEmpty -> ExportAssessment(false, "편집할 영상이 없습니다.")
            timeline.clips.size > 1 -> ExportAssessment(
                false, "구간 연결도 자동 저장할 수 있습니다. 같은 원본의 구간은 부분 처리를 우선 시도하며, 필요하면 전체 처리로 이어집니다."
            )
            else -> {
                val clip = timeline.clips.single()
                val inspection = FastMp4Trimmer.inspect(context, clip)
                if (inspection.plan != null) {
                    ExportAssessment(true, "현재 구간은 재인코딩 없이 빠르게 저장할 수 있습니다.", directCopy = true)
                } else {
                    ExportAssessment(
                        fastAvailable = false,
                        explanation = inspection.reason?.explanation
                            ?: "현재 영상에서 고속 저장을 사용할 수 없습니다.",
                        suggestion = if (inspection.reason == FastMp4Trimmer.UnavailableReason.KEYFRAME) {
                            nearbyKeyframe(context, clip)?.let {
                                FastCutSuggestion.createUs(timeline, listOf(it to clip.endUs))
                            }
                        } else null,
                    )
                }
            }
        }
        if (timeline.isEmpty) return@withContext assessment
        if (!assessment.fastAvailable && assessment.suggestion == null) {
            NativeSmartCutTrimmer.suggestCopyRangesUs(context, timeline, onProgress)?.let { ranges ->
                if (ranges == timeline.clips.map { it.startUs to it.endUs }) {
                    assessment = ExportAssessment(true,
                        "현재 구간은 영상 재인코딩 없이 고속 저장할 수 있습니다. 소리는 필요하면 변환합니다.")
                } else {
                    assessment = assessment.copy(suggestion = FastCutSuggestion.createUs(timeline, ranges))
                }
            }
        }
        val direct = assessment.directCopy && dateMode != SaveDateMode.ORIGINAL
        val estimatedBytes = ExportStorage.estimateOutputBytes(context, timeline, direct)
        assessment.copy(
            explanation = assessment.explanation + "\n" +
                ExportStorage.explanation(context, estimatedBytes, direct),
        )
    }

    private fun nearbyKeyframe(context: Context, clip: VideoClip): Long? {
        if (clip.startUs <= 0L) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, Uri.parse(clip.uri), null)
            val video = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return null
            extractor.selectTrack(video)
            extractor.seekTo(clip.startUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
            val candidateUs = extractor.sampleTime
            if (candidateUs < clip.startUs ||
                candidateUs > clip.endUs - 100_000L
            ) return null
            val candidate = clip.withRangeUs(candidateUs, clip.endUs)
            if (FastMp4Trimmer.prepare(context, candidate) != null &&
                candidateUs != clip.startUs
            ) candidateUs else null
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}


