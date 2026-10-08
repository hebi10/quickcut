package com.hebi.quickcut.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * MediaStore DATE_TAKEN / DATE_MODIFIED are indexed, read-only fields.
 * Never assume that a newly inserted media item can set DATE_TAKEN directly.
 */
object OriginalMediaDates {
    fun resolve(context: Context, originalUri: Uri): OriginalTimestamp? {
        val resolver = context.contentResolver
        val mediaUri = runCatching {
            if (DocumentsContract.isDocumentUri(context, originalUri)) {
                MediaStore.getMediaUri(context, originalUri)
            } else originalUri
        }.getOrNull()

        if (mediaUri != null) {
            findColumnMillis(context, mediaUri, MediaStore.MediaColumns.DATE_TAKEN, false)
                ?.let { return OriginalTimestamp(it, "촬영 날짜") }
        }
        // Some non-MediaStore video providers expose creation time only in MP4 metadata.
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, originalUri)
                parseMp4Date(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                )
            } finally {
                retriever.release()
            }
        }.getOrNull()?.let { return OriginalTimestamp(it, "영상 메타데이터") }

        // Fallback to file modified time; this may differ from the actual recording date.
        runCatching {
            if (DocumentsContract.isDocumentUri(context, originalUri)) {
                findColumnMillis(
                    context, originalUri, DocumentsContract.Document.COLUMN_LAST_MODIFIED, false
                )
            } else null
        }.getOrNull()?.let { return OriginalTimestamp(it, "원본 파일 수정 날짜") }

        if (mediaUri != null) {
            findColumnMillis(context, mediaUri, MediaStore.MediaColumns.DATE_MODIFIED, true)
                ?.let { return OriginalTimestamp(it, "원본 파일 수정 날짜") }
        }
        return null
    }

    fun parseMp4Date(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()
        val parsers = listOf<(String) -> Long>(
            { Instant.parse(it).toEpochMilli() },
            { OffsetDateTime.parse(it).toInstant().toEpochMilli() },
            {
                OffsetDateTime.parse(it, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSX", Locale.US))
                    .toInstant().toEpochMilli()
            },
            {
                OffsetDateTime.parse(it, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX", Locale.US))
                    .toInstant().toEpochMilli()
            },
        )
        return parsers.firstNotNullOfOrNull { parse -> runCatching { parse(value) }.getOrNull() }
            ?.takeIf(::isPlausible)
    }

    private fun findColumnMillis(
        context: Context,
        uri: Uri,
        column: String,
        isSeconds: Boolean,
    ): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(column)
            if (index < 0 || cursor.isNull(index)) return@use null
            val raw = cursor.getLong(index)
            val value = if (isSeconds) raw * 1000L else raw
            value.takeIf(::isPlausible)
        }
    }.getOrNull()

    private fun isPlausible(value: Long): Boolean =
        value in 315_532_800_000L..4_102_444_800_000L // 1980 through year 2100.
}
