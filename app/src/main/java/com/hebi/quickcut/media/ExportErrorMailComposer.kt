package com.hebi.quickcut.media

/** Only a validated local failure record can be prepared for user-initiated email. */
internal data class ExportErrorMail(
    val recipient: String,
    val subject: String,
    val body: String,
)

internal object ExportErrorMailComposer {
    const val SUPPORT_EMAIL = "asdlkj0104@gmail.com"

    fun forFailure(record: ExportDiagnosticRecord): ExportErrorMail? {
        if (record.status != ExportDiagnosticStatus.FAILED || !record.isValid()) return null
        val version = record.facts.app.replace(Regex("[^A-Za-z0-9._-]"), "").take(32)
            .ifBlank { "unknown" }
        val failure = record.failureType ?: "Exception"
        val subject = "QuickCut " + version + " 오류 제보 · " + failure +
            " · " + record.operationId.take(8)
        val body = buildString {
            appendLine("QuickCut 오류 제보")
            appendLine()
            appendLine("아래 내용은 기기에 보관된 진단 기록입니다.")
            appendLine("원본 영상·파일명·URI·경로·원시 오류 및 첨부파일은 포함하지 않았습니다.")
            appendLine()
            appendLine(record.text)
            appendLine()
            appendLine()
            appendLine("추가 설명(선택):")
        }
        return ExportErrorMail(SUPPORT_EMAIL, subject, body)
    }
}
