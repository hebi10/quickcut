package com.hebi.quickcut.batch

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.FastCutSuggestion

enum class BatchExportChoice { EXACT, RECOMMENDED, SKIP }

/** One analysis result, including files that cannot currently be exported. */
data class BatchReviewedFile(
    val uri: String,
    val displayName: String,
    val requested: EditTimeline?,
    val suggestion: FastCutSuggestion? = null,
    val fastAvailable: Boolean = false,
    val unavailableReason: String? = null,
    val explanation: String? = null,
) {
    val canRecommend: Boolean get() = requested != null &&
        requested.clips.isNotEmpty() && requested.clips.all { it.uri == uri } &&
        suggestion?.applyTo(requested) != null
}

/** A resolved choice keeps the exact reviewed timeline, including sub-millisecond ranges. */
data class BatchReviewedExport(
    val uri: String,
    val choice: BatchExportChoice,
    val requested: EditTimeline?,
    val timeline: EditTimeline?,
    val exportMode: ExportMode?,
    val skipReason: String? = null,
) {
    fun timelineFor(current: EditTimeline): EditTimeline? = timeline.takeIf { current == requested }
}

object BatchExportChoicePolicy {
    fun defaults(files: List<BatchReviewedFile>): Map<String, BatchExportChoice> =
        files.associate { it.uri to if (it.requested == null) BatchExportChoice.SKIP else BatchExportChoice.EXACT }

    /** A missing/foreign entry or unsupported choice rejects the entire review. */
    fun resolve(
        files: List<BatchReviewedFile>,
        choices: Map<String, BatchExportChoice>,
    ): List<BatchReviewedExport>? {
        val uris = files.map { it.uri }.toSet()
        if (uris.size != files.size || choices.keys != uris) return null
        return files.map { file ->
            val choice = choices.getValue(file.uri)
            val requested = file.requested
            if (requested != null && (requested.isEmpty || requested.clips.any { it.uri != file.uri })) return null
            when (choice) {
                BatchExportChoice.EXACT -> {
                    requested ?: return null
                    BatchReviewedExport(file.uri, choice, requested, requested, ExportMode.ALLOW_REENCODE)
                }
                BatchExportChoice.RECOMMENDED -> {
                    if (!file.canRecommend) return null
                    BatchReviewedExport(file.uri, choice, requested,
                        file.suggestion!!.adjusted, ExportMode.FAST_ONLY)
                }
                BatchExportChoice.SKIP -> BatchReviewedExport(file.uri, choice, requested, null, null,
                    file.unavailableReason ?: "사용자가 제외한 영상입니다.")
            }
        }
    }
}
