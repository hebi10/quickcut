package com.hebi.quickcut.media

enum class ExportStage(val label: String) {
    PREPARING("저장 준비 중"),
    ANALYZING("원본 분석 중"),
    COPYING("원본 데이터 복사 중"),
    PROCESSING("영상·소리 처리 중"),
    VALIDATING("저장 결과 검사 중"),
    SAVING("갤러리 파일 저장 중"),
    PUBLISHING("갤러리 등록 중"),
    COMPLETE("저장 완료"),
}

/** Only enumerated/numeric media facts enter the local report, never an input URI or message. */
internal data class ExportDiagnosticFacts(
    val app: String,
    val device: String,
    val androidApi: Int,
    val sourceCount: Int,
    val sourceDurationMs: Long,
    val retainedDurationUs: Long,
    val clipCount: Int,
    val sourceBytes: Long? = null,
    val videoMime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Number? = null,
    val colorStandard: Int? = null,
    val colorTransfer: Int? = null,
    val audioMime: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
    val cacheAvailableBytes: Long? = null,
    val galleryAvailableBytes: Long? = null,
) {
    fun text(stage: ExportStage, status: String, elapsedMs: Long,
             reasons: ExportDiagnostics, method: String?, failureType: String?): String = buildString {
        appendLine("QuickCut 기기 내 저장 진단")
        appendLine("앱: ${safeLabel(app)}")
        appendLine("기기: ${safeLabel(device)} / Android API $androidApi")
        appendLine("상태: ${safeLabel(status)} / 단계: ${stage.label}")
        appendLine("경과 시간(ms): $elapsedMs")
        appendLine("원본 수: $sourceCount / 구간 수: $clipCount")
        appendLine("첫 원본 길이(ms): $sourceDurationMs / 저장 길이(us): $retainedDurationUs")
        appendLine("첫 원본 크기(bytes): ${sourceBytes ?: "확인 불가"}")
        appendLine("첫 원본 영상: ${safeMime(videoMime)} / ${width ?: "?"}×${height ?: "?"} / fps ${frameRate ?: "?"}")
        appendLine("색상 표준/전달: ${colorStandard ?: "?"}/${colorTransfer ?: "?"}")
        appendLine("첫 원본 소리: ${safeMime(audioMime)} / 채널 ${audioChannels ?: "?"} / Hz ${audioSampleRate ?: "?"}")
        appendLine("시작 시 여유 공간(bytes): 캐시 ${cacheAvailableBytes ?: "?"} / 갤러리 ${galleryAvailableBytes ?: "?"}")
        appendLine("처리 방식: ${safeLabel(method ?: "확인 전")}")
        appendLine("원본 복사 사유: ${reasons.copyReason?.name ?: "없음"}")
        appendLine("부분 처리 사유: ${reasons.smartCutReason?.name ?: "없음"}")
        failureType?.let { appendLine("오류 종류: ${safeLabel(it)}") }
        append("원본 경로·이름·내용은 기록하지 않습니다. 이 기록은 자동 전송되지 않습니다.")
    }
    companion object {
        private fun safeLabel(value: String) = value.replace(Regex("[^\\p{L}\\p{N} ._+()-]"), "_").take(96)
        private fun safeMime(value: String?): String = value?.takeIf {
            it.length <= 64 && it.matches(Regex("(?:video|audio)/[A-Za-z0-9.+_-]+"))
        } ?: "없음 또는 확인 불가"
    }
}
