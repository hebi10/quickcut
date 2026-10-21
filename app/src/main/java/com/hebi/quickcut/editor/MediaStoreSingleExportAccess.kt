package com.hebi.quickcut.editor

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.hebi.quickcut.media.MediaReader
import java.io.IOException

/** Only app-owned MediaStore video rows may be adopted or removed. Call off Main. */
class MediaStoreSingleExportAccess(private val context: Context) : SingleExportOutputAccess {
    private val resolver get() = context.contentResolver
    private val outputDirectory = Environment.DIRECTORY_MOVIES + "/QuickCut"

    override fun inspect(snapshot: SingleExportSnapshot): SingleExportOutputState {
        val uri = outputUri(snapshot) ?: return SingleExportOutputState.UNCERTAIN
        val row = resolver.query(uri, arrayOf(MediaStore.Video.Media.IS_PENDING,
            MediaStore.Video.Media.OWNER_PACKAGE_NAME, MediaStore.Video.Media.RELATIVE_PATH),
            null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use SingleExportOutputState.ABSENT
            if (cursor.isNull(0) || cursor.getString(1) != context.packageName)
                return@use SingleExportOutputState.UNCERTAIN
            when (cursor.getInt(0)) {
                1 -> if (cursor.getString(2)?.trimEnd('/') == outputDirectory)
                    SingleExportOutputState.OWNED_PENDING else SingleExportOutputState.UNCERTAIN
                0 -> SingleExportOutputState.VERIFIED
                else -> SingleExportOutputState.UNCERTAIN
            }
        } ?: return SingleExportOutputState.UNCERTAIN
        if (row == SingleExportOutputState.VERIFIED) {
            // Published rows may have been moved, but ownership and the original
            // duration/audio/date contract must still match before adoption.
            MediaReader.validateExport(context, uri, snapshot.expectedDurationMs,
                snapshot.expectedAudio, snapshot.expectedDateMs)
        }
        return row
    }

    override fun removePending(snapshot: SingleExportSnapshot) {
        val uri = outputUri(snapshot) ?: throw IOException("출력 파일을 확인할 수 없습니다.")
        if (inspect(snapshot) != SingleExportOutputState.OWNED_PENDING)
            throw IOException("미완성 파일의 소유권을 확인할 수 없습니다.")
        // Guard the deletion as well as the preceding query against a row changing.
        resolver.delete(uri,
            "${MediaStore.Video.Media.IS_PENDING} = ? AND ${MediaStore.Video.Media.OWNER_PACKAGE_NAME} = ? AND " +
                "(${MediaStore.Video.Media.RELATIVE_PATH} = ? OR ${MediaStore.Video.Media.RELATIVE_PATH} = ?)",
            arrayOf("1", context.packageName, outputDirectory, "$outputDirectory/"))
        val absent = resolver.query(uri, arrayOf(MediaStore.Video.Media._ID), null, null, null)
            ?.use { !it.moveToFirst() } == true
        if (!absent) throw IOException("미완성 파일 삭제를 확인할 수 없습니다.")
    }

    private fun outputUri(snapshot: SingleExportSnapshot): Uri? {
        val uri = snapshot.outputUri?.let(Uri::parse) ?: return null
        val parts = uri.pathSegments
        return uri.takeIf {
            it.scheme == "content" && it.authority == "media" && it.query == null && it.fragment == null &&
                parts.size == 4 && parts[0].isNotBlank() && parts[1] == "video" &&
                parts[2] == "media" && (parts[3].toLongOrNull() ?: 0L) > 0L
        }
    }
}
