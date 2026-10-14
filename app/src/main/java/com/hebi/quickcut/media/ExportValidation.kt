package com.hebi.quickcut.media

import java.io.IOException
import kotlin.math.abs

internal object ExportValidation {
    const val DURATION_TOLERANCE_MS = 300L

    fun requireMetadata(
        hasVideo: Boolean,
        durationMs: Long,
        hasAudio: Boolean,
        expectedDurationMs: Long? = null,
        expectAudio: Boolean? = null,
    ) {
        if (!hasVideo || durationMs <= 0L) throw IOException("저장된 영상을 재생할 수 없습니다.")
        if (expectedDurationMs != null &&
            (expectedDurationMs <= 0L || abs(durationMs - expectedDurationMs) > DURATION_TOLERANCE_MS)
        ) {
            throw IOException("저장된 영상 길이가 편집 구간과 일치하지 않습니다.")
        }
        if (expectAudio != null && hasAudio != expectAudio) {
            throw IOException("저장된 영상의 오디오가 편집 설정과 일치하지 않습니다.")
        }
    }

    fun requireTimestamp(expectedTimestampMs: Long, actualTimestampMs: Long?) {
        if (actualTimestampMs == null || abs(actualTimestampMs - expectedTimestampMs) > 3_000L) {
            throw IOException("원본 날짜를 적용하지 못했습니다. 저장을 취소했습니다.")
        }
    }
}
