package com.hebi.quickcut.media

import org.junit.Assert.*
import org.junit.Test

class ExportFailureEvidenceTest {
    @Test fun onlyKnownCodecMetadataCanEnterTheReport() {
        val unsafe = ExportFailureEvidence(98765, "content://private/source.mp4",
            ExportCodecKind.VIDEO_DECODER, -123, true, false)
        val safe = unsafe.sanitized()
        assertNull(safe.media3ErrorCode)
        assertNull(safe.codecName)
        assertNull(safe.codecErrorCode)
        assertEquals(ExportCodecKind.VIDEO_DECODER, safe.codecKind)
        assertEquals(true, safe.recoverable)
        assertFalse(safe.text.contains("source.mp4"))
        assertFalse(safe.text.contains("98765"))
    }

    @Test fun resourceReclaimAndInsufficientResourceRemainDifferentFromMuxTimeout() {
        val reclaimed = ExportFailureEvidence(codecName = "c2.qti.avc.decoder",
            codecKind = ExportCodecKind.VIDEO_DECODER, codecErrorCode = 1101,
            recoverable = true, transient = false).sanitized()
        val insufficient = reclaimed.copy(codecErrorCode = 1100, recoverable = false, transient = true)
        val timeout = ExportFailureEvidence(media3ErrorCode = 7002).sanitized()
        assertTrue(reclaimed.text.contains("1101"))
        assertTrue(insufficient.text.contains("1100"))
        assertTrue(timeout.text.contains("ERROR_CODE_MUXING_TIMEOUT"))
        assertFalse(timeout.text.contains("코덱 자원 오류"))
    }

    @Test fun cyclicOrDeepThrowableCausesAreBoundedWithoutReadingTheirMessages() {
        val first = IllegalStateException("private first")
        val second = IllegalStateException("private second")
        first.initCause(second)
        second.initCause(first)
        assertEquals(listOf(first, second), ExportFailureEvidence.boundedCauses(first))
        var deep: Throwable = IllegalStateException("private bottom")
        repeat(20) { deep = IllegalStateException("private", deep) }
        assertEquals(8, ExportFailureEvidence.boundedCauses(deep).size)
    }
}
