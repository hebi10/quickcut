package com.hebi.quickcut.media

enum class ExportStage(val label: String) {
    PREPARING("저장 준비 중"),
    ANALYZING("원본 분석 중"),
    COPYING("원본 데이터 복사 중"),
    PROCESSING("영상·소리 처리 중"),
    NATIVE_PROCESSING("원본 복사·필요 구간 처리 중"),
    MEDIA3_PROCESSING("자동 저장 처리 중"),
    VIDEO_ENCODING("영상 인코딩 중"),
    AUDIO_ENCODING("소리 인코딩 중"),
    RECOVERING("저장 자원 다시 준비 중"),
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
             reasons: ExportDiagnostics, method: String?, failureType: String?,
             operationId: String? = null, stageMs: Map<ExportStage, Long> = emptyMap()): String = buildString {
        appendLine("QuickCut 기기 내 저장 진단")
        operationId?.let { appendLine("작업 ID: $it") }
        appendLine("앱: ${safeLabel(app)}")
        appendLine("기기: ${safeLabel(device)} / Android API $androidApi")
        appendLine("상태: ${safeLabel(status)} / 단계: ${stage.label}")
        appendLine("경과 시간(ms): $elapsedMs")
        stageMs.forEach { (stage, milliseconds) -> appendLine("단계 시간(ms) ${stage.label}: $milliseconds") }
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
    /** Optional malformed metadata must not hide a final operation outcome. */
    internal fun sanitized(): ExportDiagnosticFacts = copy(
        app = safeLabel(app), device = safeLabel(device),
        sourceBytes = sourceBytes?.takeIf { it >= 0 },
        videoMime = videoMime?.takeIf { it == safeMime(it) },
        audioMime = audioMime?.takeIf { it == safeMime(it) },
        width = width?.takeIf { it >= 0 }, height = height?.takeIf { it >= 0 },
        frameRate = frameRate?.takeIf { it.toDouble().isFinite() && it.toDouble() in 0.0..1000.0 },
        audioChannels = audioChannels?.takeIf { it >= 0 }, audioSampleRate = audioSampleRate?.takeIf { it >= 0 },
        cacheAvailableBytes = cacheAvailableBytes?.takeIf { it >= 0 },
        galleryAvailableBytes = galleryAvailableBytes?.takeIf { it >= 0 })

    internal fun isValid(): Boolean = androidApi in 1..999 && sourceCount >= 0 && clipCount >= 0 &&
        sourceDurationMs >= 0 && retainedDurationUs >= 0 && app.length <= 96 && device.length <= 96 &&
        !app.contains('/') && !device.contains('/') && !app.contains('\n') && !device.contains('\n') &&
        listOf(sourceBytes, cacheAvailableBytes, galleryAvailableBytes).all { it == null || it >= 0 } &&
        listOf(width, height, audioChannels, audioSampleRate).all { it == null || it >= 0 } &&
        (frameRate == null || (frameRate.toDouble().isFinite() && frameRate.toDouble() in 0.0..1000.0)) &&
        listOf(videoMime, audioMime).all { it == null || it == safeMime(it) }

    companion object {
        private fun safeLabel(value: String) = value.replace(Regex("[^\\p{L}\\p{N} ._+()-]"), "_").take(96)
        private fun safeMime(value: String?): String = value?.takeIf {
            it.length <= 64 && it.matches(Regex("(?:video|audio)/[A-Za-z0-9.+_-]+"))
        } ?: "없음 또는 확인 불가"
    }
}
