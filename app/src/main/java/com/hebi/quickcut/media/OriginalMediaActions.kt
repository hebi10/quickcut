package com.hebi.quickcut.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import com.hebi.quickcut.editor.OriginalAction

/**
 * Only produces a system-owned confirmation request. It NEVER deletes anything directly.
 * Unsupported/cloud/document-provider URIs fail closed, leaving every original untouched.
 */
object OriginalMediaActions {
    fun prepareRequest(
        context: Context,
        originalSources: List<String>,
        savedOutput: Uri,
        action: OriginalAction,
    ): IntentSenderRequest {
        require(action != OriginalAction.KEEP) { "원본 유지 상태에서는 삭제 요청을 생성할 수 없습니다." }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("시스템 원본 삭제 승인 기능은 Android 11 이상에서 지원합니다.")
        }
        val sources = originalSources.distinct()
        require(sources.isNotEmpty()) { "삭제할 원본이 없습니다." }
        if (sources.size > 2_000) {
            throw UnsupportedOperationException("한 번에 삭제 승인할 수 있는 원본은 최대 2,000개입니다.")
        }

        val mediaUris = sources.map { source ->
            val originalUri = Uri.parse(source)
            val uri = if (originalUri.scheme == "content" &&
                originalUri.authority == MediaStore.AUTHORITY
            ) {
                originalUri
            } else if (DocumentsContract.isDocumentUri(context, originalUri)) {
                MediaStore.getMediaUri(context, originalUri)
                    ?: throw UnsupportedOperationException(
                        "이 파일 제공자는 원본 삭제를 지원하지 않습니다. 원본은 유지됩니다."
                    )
            } else {
                throw UnsupportedOperationException(
                    "갤러리 외부 파일은 자동으로 삭제하지 않습니다. 원본은 유지됩니다."
                )
            }
            if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY ||
                runCatching { ContentUris.parseId(uri) }.getOrNull()?.let { it > 0L } != true
            ) {
                throw UnsupportedOperationException("원본 미디어를 식별할 수 없어 삭제하지 않았습니다.")
            }
            if (uri == savedOutput) {
                throw IllegalStateException("내보낸 영상이 원본 삭제 대상에 포함되어 있습니다.")
            }
            uri
        }.distinct()

        val pending = when (action) {
            OriginalAction.KEEP -> error("원본 유지 상태에서 삭제 요청이 발생했습니다.")
            OriginalAction.TRASH -> MediaStore.createTrashRequest(
                context.contentResolver, mediaUris, true,
            )
            OriginalAction.DELETE -> MediaStore.createDeleteRequest(
                context.contentResolver, mediaUris,
            )
        }
        return IntentSenderRequest.Builder(pending.intentSender).build()
    }
}
