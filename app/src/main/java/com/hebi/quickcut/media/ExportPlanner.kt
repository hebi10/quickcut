package com.hebi.quickcut.media

import android.content.Context
import com.hebi.quickcut.editor.EditTimeline
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
    val lessTrimSuggestion: FastCutSuggestion? = null,
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
            timeline.clips.any { it.crop != null || it.textLayers.isNotEmpty() ||
                (it.hasAudio && !it.muted && !it.audio.isDefault) } -> ExportAssessment(
                false, if (timeline.clips.any {
                    it.hasAudio && !it.muted && !it.audio.isDefault
                }) "볼륨·페이드 편집에는 오디오 재인코딩 승인이 필요합니다. 영상 효과도 함께 처리됩니다."
                else "크롭·텍스트 오버레이 적용에는 영상 재인코딩 승인이 필요합니다."
            )
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
                        suggestion = validatedCopySuggestion(context, timeline, outward = false),
                        lessTrimSuggestion = validatedCopySuggestion(context, timeline, outward = true),
                    )
                }
            }
        }
        if (timeline.isEmpty) return@withContext assessment
        if (!assessment.fastAvailable && assessment.suggestion == null && assessment.lessTrimSuggestion == null &&
            timeline.clips.none { it.crop != null || it.textLayers.isNotEmpty() ||
                (it.hasAudio && !it.muted && !it.audio.isDefault) }) {
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

    private fun validatedCopySuggestion(
        context: Context,
        timeline: EditTimeline,
        outward: Boolean,
    ): FastCutSuggestion? {
        val clip = timeline.clips.single()
        return KeyframeFinder.copyCandidates(context, clip, outward).firstNotNullOfOrNull { range ->
            val candidate = if (outward) FastCutSuggestion.createOutwardUs(timeline, range)
                else FastCutSuggestion.createUs(timeline, listOf(range))
            candidate?.takeIf { FastMp4Trimmer.prepare(context, it.adjusted.clips.single()) != null }
        }
    }
}
