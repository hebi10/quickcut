package com.hebi.quickcut.media

import android.media.MediaCodec
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import java.util.Collections
import java.util.IdentityHashMap

internal enum class ExportEngine { COPY, NATIVE, MEDIA3 }

internal enum class ExportCodecKind { VIDEO_DECODER, VIDEO_ENCODER, AUDIO_DECODER, AUDIO_ENCODER }

internal data class ExportFailureEvidence(
    val media3ErrorCode: Int? = null,
    val codecName: String? = null,
    val codecKind: ExportCodecKind? = null,
    val codecErrorCode: Int? = null,
    val recoverable: Boolean? = null,
    val transient: Boolean? = null,
) {
    fun sanitized(): ExportFailureEvidence = copy(
        media3ErrorCode = media3ErrorCode?.takeIf(ERROR_NAMES::containsKey),
        codecName = codecName?.takeIf { CODEC_NAME.matches(it) },
        codecErrorCode = codecErrorCode?.takeIf { it == 1100 || it == 1101 },
    )

    val text: String get() = sanitized().let { safe -> buildString {
        safe.media3ErrorCode?.let { appendLine("Media3 오류: $it (${ERROR_NAMES[it]})") }
        safe.codecName?.let { appendLine("코덱: $it") }
        safe.codecKind?.let { appendLine("코덱 역할: ${it.name}") }
        safe.codecErrorCode?.let { appendLine("코덱 자원 오류: $it") }
        safe.recoverable?.let { appendLine("코덱 복구 가능: $it") }
        safe.transient?.let { appendLine("코덱 일시 오류: $it") }
    }.trimEnd() }

    companion object {
        private val CODEC_NAME = Regex("(?:c2|OMX)\\.[A-Za-z0-9_.-]{1,120}")
        internal val ERROR_NAMES = mapOf(
            1000 to "ERROR_CODE_UNSPECIFIED", 1001 to "ERROR_CODE_FAILED_RUNTIME_CHECK",
            2000 to "ERROR_CODE_IO_UNSPECIFIED", 2001 to "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            2002 to "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT", 2003 to "ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE",
            2004 to "ERROR_CODE_IO_BAD_HTTP_STATUS", 2005 to "ERROR_CODE_IO_FILE_NOT_FOUND",
            2006 to "ERROR_CODE_IO_NO_PERMISSION", 2007 to "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED",
            2008 to "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE", 3001 to "ERROR_CODE_DECODER_INIT_FAILED",
            3002 to "ERROR_CODE_DECODING_FAILED", 3003 to "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
            4001 to "ERROR_CODE_ENCODER_INIT_FAILED", 4002 to "ERROR_CODE_ENCODING_FAILED",
            4003 to "ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED", 5001 to "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED",
            6001 to "ERROR_CODE_AUDIO_PROCESSING_FAILED", 7001 to "ERROR_CODE_MUXING_FAILED",
            7002 to "ERROR_CODE_MUXING_TIMEOUT", 7003 to "ERROR_CODE_MUXING_APPEND",
        )

        @OptIn(UnstableApi::class)
        fun capture(error: Throwable?): ExportFailureEvidence? {
            val causes = boundedCauses(error)
            val media3 = causes.filterIsInstance<ExportException>().firstOrNull()
            val codec = causes.filterIsInstance<MediaCodec.CodecException>().firstOrNull()
            if (media3 == null && codec == null) return null
            val info = media3?.codecInfo
            return ExportFailureEvidence(media3?.errorCode, info?.name,
                info?.let {
                    when {
                        it.isVideo && it.isDecoder -> ExportCodecKind.VIDEO_DECODER
                        it.isVideo -> ExportCodecKind.VIDEO_ENCODER
                        it.isDecoder -> ExportCodecKind.AUDIO_DECODER
                        else -> ExportCodecKind.AUDIO_ENCODER
                    }
                }, codec?.errorCode, codec?.isRecoverable, codec?.isTransient).sanitized()
        }

        internal fun boundedCauses(error: Throwable?): List<Throwable> {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val result = mutableListOf<Throwable>()
            var current = error
            while (current != null && result.size < 8 && seen.add(current)) {
                result += current
                current = current.cause
            }
            return result
        }
    }
}

internal fun exportFailureMessage(error: Throwable): String {
    val evidence = ExportFailureEvidence.capture(error)
    return when {
        evidence?.media3ErrorCode == 2006 ->
            "원본 영상의 읽기 권한을 사용할 수 없습니다. 영상을 다시 선택해 주세요."
        evidence?.codecErrorCode == 1100 || evidence?.codecErrorCode == 1101 ||
            evidence?.transient == true || evidence?.recoverable == true ->
            "기기의 영상 처리 자원을 사용할 수 없어 저장을 중단했습니다. 다른 영상 재생을 종료한 뒤 다시 시도해 주세요."
        evidence?.media3ErrorCode == 7002 ->
            "영상 처리의 진행 응답이 멈춰 저장을 중단했습니다. 저장 진단에서 오류 코드를 확인할 수 있습니다."
        evidence?.media3ErrorCode != null ->
            "영상 처리 중 오류가 발생했습니다. 오류 코드 ${evidence.media3ErrorCode} · 저장 진단을 확인해 주세요."
        else -> error.localizedMessage ?: "다시 시도해 주세요."
    }
}
