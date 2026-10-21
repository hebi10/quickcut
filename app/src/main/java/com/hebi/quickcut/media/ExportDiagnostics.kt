package com.hebi.quickcut.media

enum class FastExportReason(val nativeCode: Long?, val explanation: String) {
    UNKNOWN(0, "원인 확인 불가: 고속 처리 엔진이 구체적인 사유를 반환하지 않았습니다."),
    SOURCE_UNREADABLE(1, "원본 영상 정보를 끝까지 읽거나 분석하지 못했습니다."),
    VIDEO_CODEC(2, "영상 코덱이 고속 처리에서 지원하는 H.264·H.265가 아닙니다."),
    PIXEL_FORMAT(3, "영상의 색상 표본 형식이 지원 범위인 8비트 4:2:0과 다릅니다."),
    PICTURE_STRUCTURE(4, "인터레이스 영상이거나 프레임 구조를 확인할 수 없습니다."),
    FRAME_RATE(5, "프레임 간격이 일정하지 않거나 프레임 속도가 지원 범위인 120fps 이하가 아닙니다."),
    DIMENSIONS(6, "영상 크기를 확인할 수 없거나 가로·세로가 지원 범위인 8192픽셀을 넘습니다."),
    COLOR_FORMAT(7, "HDR·광색역 등 고속 처리에서 지원하지 않는 색상 정보가 있습니다."),
    AUDIO_FORMAT(8, "소리 형식 또는 채널 구성이 지원 범위인 AAC 1~2채널과 다릅니다."),
    TRACK_LAYOUT(9, "영상·소리 트랙이 여러 개이거나 자막 등 추가 트랙이 있습니다."),
    SOURCE_DURATION(10, "원본 길이와 선택 구간의 끝을 정확히 일치시킬 수 없습니다."),
    CONTAINER(11, "파일 컨테이너가 부분 처리에서 지원하는 MP4가 아닙니다."),
    ROTATION(12, "회전·화면 변환 정보를 고속 처리에서 그대로 보존할 수 없습니다."),
    METADATA(13, "처리 중 바뀌는 코덱 설정이나 지원하지 않는 부가 정보가 있습니다."),
    CODEC_CONFIGURATION(14, "코덱의 세부 설정이 지원 범위와 다르거나 연결 호환성을 확인할 수 없습니다."),
    UNSAFE_SPLICE(15, "구간 연결 지점에서 프레임이 누락될 수 있어 부분 처리를 사용할 수 없습니다."),
    NO_COPY_SEGMENT(16, "선택 구간에 그대로 복사할 수 있는 완전한 프레임 묶음이 없습니다."),
    EXACT_BOUNDARY(17, "부분 처리를 적용하면 요청한 자르기 위치가 달라집니다."),
    TIMESTAMPS(18, "프레임 시간 정보가 일정하지 않거나 중복·누락되어 안전하게 복사할 수 없습니다."),
    PACKET_STRUCTURE(19, "영상 데이터가 손상되었거나 고속 처리에서 지원하지 않는 구조입니다."),
    OUTPUT_VALIDATION(20, "부분 처리 결과의 프레임·길이·소리·날짜 또는 MP4 저장 검증을 통과하지 못했습니다."),
    PROCESSING_FAILED(21, "부분 처리 중 오류가 발생했습니다. 세부 원인은 확인할 수 없습니다."),
    ENGINE_UNAVAILABLE(null, "이 기기에서 고속 부분 처리 엔진을 불러올 수 없습니다."),
    SOURCE_ACCESS(null, "원본 파일을 고속 처리에 필요한 직접 탐색 방식으로 읽을 수 없습니다."),
    MULTIPLE_SOURCES(null, "서로 다른 원본을 연결하는 편집은 고속 부분 처리에서 지원하지 않습니다."),
    MIXED_AUDIO_POLICY(null, "구간마다 소리 사용 설정이 달라 고속 부분 처리를 사용할 수 없습니다."),
    RANGE_COUNT(null, "고속 부분 처리는 한 번에 1~256개 구간을 지원합니다."),
    INVALID_RANGE(null, "선택 구간의 시간을 고속 처리에 필요한 형식으로 표현할 수 없습니다."),
    MULTIPLE_CLIPS(null, "여러 구간을 연결하는 편집은 단일 구간 원본 복사를 사용할 수 없습니다."),
    COPY_FAILED(null, "원본 복사 중 오류가 발생하거나 복사 결과 검증을 통과하지 못했습니다."),
    NO_VIDEO(null, "원본에서 영상 트랙을 찾을 수 없습니다."),
    KEYFRAME(null, "자르기 시작 위치가 키프레임(재생 기준 프레임)과 맞지 않습니다."),
    UNSAFE_START(null, "시작 지점 앞의 프레임이 재생 순서에 섞여 있어 해당 위치에서 복사할 수 없습니다."),
    REORDERED_END(null, "프레임의 저장·재생 순서가 달라 선택한 끝 지점까지 단순 복사할 수 없습니다."),
    AUDIO_CODEC(null, "소리 코덱이 원본 복사에서 지원하는 AAC가 아닙니다."),
    ;

    companion object {
        fun fromNativeCode(code: Long): FastExportReason =
            entries.firstOrNull { it.nativeCode == code } ?: UNKNOWN

        fun fromStoredName(name: String?): FastExportReason? =
            name?.let { value -> entries.firstOrNull { it.name == value } ?: UNKNOWN }
    }
}

data class ExportDiagnostics(
    val copyReason: FastExportReason? = null,
    val smartCutReason: FastExportReason? = null,
) {
    fun explanation(includeSmartCut: Boolean = true): String = listOfNotNull(
        copyReason?.let { "원본 복사 제한: ${it.explanation}" },
        smartCutReason?.takeIf { includeSmartCut }?.let { "고속 부분 처리 제한: ${it.explanation}" },
    ).joinToString("\n")
}
