package com.hebi.quickcut.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import com.hebi.quickcut.editor.EditTimeline
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.util.Locale

internal data class ExportSourceSize(
    val sourceBytes: Long?,
    val sourceDurationMs: Long,
    val retainedDurationMs: Long,
)

internal data class ExportSpaceRequirements(
    val cacheBytes: Long,
    val galleryBytes: Long,
    val sharedBytes: Long,
) {
    fun fits(cacheAvailableBytes: Long?, galleryAvailableBytes: Long?): Boolean =
        (cacheAvailableBytes == null || cacheAvailableBytes >= cacheBytes) &&
            (galleryAvailableBytes == null || galleryAvailableBytes >= galleryBytes) &&
            (cacheAvailableBytes == null || cacheAvailableBytes >= sharedBytes) &&
            (galleryAvailableBytes == null || galleryAvailableBytes >= sharedBytes)
}

internal object ExportStoragePolicy {
    private const val SPACE_RESERVE_BYTES = 16L * 1024L * 1024L
    private const val MIN_ESTIMATED_OUTPUT_BYTES = 1024L * 1024L
    private const val FALLBACK_BYTES_PER_MS = 1_500L // 12Mbps when a provider hides its size.

    fun requirements(outputBytes: Long, directWrite: Boolean, sharedVolume: Boolean): ExportSpaceRequirements {
        require(outputBytes >= 0L)
        val galleryBytes = saturatedAdd(outputBytes, SPACE_RESERVE_BYTES)
        val cacheBytes = if (directWrite) 0L else galleryBytes
        val sharedBytes = when {
            !sharedVolume -> 0L
            directWrite -> galleryBytes
            else -> saturatedAdd(saturatedAdd(outputBytes, outputBytes), SPACE_RESERVE_BYTES)
        }
        return ExportSpaceRequirements(cacheBytes, galleryBytes, sharedBytes)
    }

    fun estimateOutputBytes(sources: List<ExportSourceSize>, directWrite: Boolean): Long {
        val mediaBytes = sources.fold(0L) { total, source ->
            require(source.sourceDurationMs > 0L && source.retainedDurationMs > 0L)
            val retainedBytes = source.sourceBytes?.takeIf { it > 0L }?.let { bytes ->
                scaledBytes(bytes, source.retainedDurationMs, source.sourceDurationMs)
            } ?: scaledBytes(source.retainedDurationMs, FALLBACK_BYTES_PER_MS, 1L)
            saturatedAdd(total, retainedBytes)
        }
        // Container overhead and a wider margin for Media3's chosen output bitrate.
        return scaledBytes(mediaBytes, if (directWrite) 11L else 15L, 10L)
            .coerceAtLeast(MIN_ESTIMATED_OUTPUT_BYTES)
    }

    private fun saturatedAdd(first: Long, second: Long): Long =
        if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second

    private fun scaledBytes(value: Long, numerator: Long, denominator: Long): Long {
        val divisor = BigInteger.valueOf(denominator)
        val rounded = BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator))
            .add(divisor.subtract(BigInteger.ONE)).divide(divisor)
        return rounded.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
    }
}

/** Preflight is an estimate; filesystem/MediaStore write errors still propagate. */
internal object ExportStorage {
    fun estimateOutputBytes(context: Context, timeline: EditTimeline, directWrite: Boolean): Long {
        val sizes = mutableMapOf<String, Long?>()
        val sources = timeline.clips.map { clip ->
            val size = if (sizes.containsKey(clip.uri)) sizes[clip.uri] else {
                sourceSize(context, Uri.parse(clip.uri)).also { sizes[clip.uri] = it }
            }
            ExportSourceSize(size, clip.sourceDurationMs, clip.durationMs)
        }
        return ExportStoragePolicy.estimateOutputBytes(sources, directWrite)
    }

    fun requireSpace(context: Context, outputBytes: Long, directWrite: Boolean) {
        val available = availableStorage(context)
        val required = ExportStoragePolicy.requirements(outputBytes, directWrite, available.sharedVolume)
        if (!required.fits(available.cacheBytes, available.galleryBytes)) {
            throw IOException("저장 공간이 부족합니다. " + explanation(outputBytes, required, directWrite))
        }
    }

    fun explanation(context: Context, outputBytes: Long, directWrite: Boolean): String {
        val available = availableStorage(context)
        val required = ExportStoragePolicy.requirements(outputBytes, directWrite, available.sharedVolume)
        val shortage = if (required.fits(available.cacheBytes, available.galleryBytes)) "" else " 현재 저장 공간이 부족합니다."
        return explanation(outputBytes, required, directWrite) + shortage
    }

    private fun explanation(outputBytes: Long, required: ExportSpaceRequirements, directWrite: Boolean): String {
        val allocation = when {
            directWrite -> "갤러리에 약 " + megabytes(required.galleryBytes) + "MB 여유 공간이 필요합니다."
            required.sharedBytes > 0L -> "임시 파일을 포함해 같은 저장소에 약 " + megabytes(required.sharedBytes) + "MB 여유 공간이 필요합니다."
            else -> "캐시와 갤러리에 각각 약 " + megabytes(required.galleryBytes) + "MB 여유 공간이 필요합니다."
        }
        return "예상 출력 약 " + megabytes(outputBytes) + "MB (추정치). " + allocation +
            " 실제 크기는 영상 형식에 따라 달라질 수 있습니다."
    }

    private fun megabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes / 1_048_576.0)

    private fun sourceSize(context: Context, uri: Uri): Long? {
        if (uri.scheme == "file") return uri.path?.let { File(it).length().takeIf { bytes -> bytes > 0L } }
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) {
                    cursor.getLong(index).takeIf { it > 0L }
                } else null
            }
        }.getOrNull()?.let { return it }
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0L }
                    ?: descriptor.parcelFileDescriptor.statSize.takeIf { it > 0L }
            }
        }.getOrNull()
    }

    private data class AvailableStorage(val cacheBytes: Long?, val galleryBytes: Long?, val sharedVolume: Boolean)

    @Suppress("DEPRECATION")
    private fun availableStorage(context: Context): AvailableStorage {
        // EXTERNAL_CONTENT_URI inserts onto the primary shared media volume.
        val galleryRoot = Environment.getExternalStorageDirectory()
        val manager = context.getSystemService(StorageManager::class.java)
        val sameVolume = runCatching {
            manager.getUuidForPath(context.cacheDir) == manager.getUuidForPath(galleryRoot)
        }.getOrDefault(true)
        return AvailableStorage(availableBytes(context.cacheDir), availableBytes(galleryRoot), sameVolume)
    }

    private fun availableBytes(directory: File): Long? =
        runCatching { StatFs(directory.absolutePath).availableBytes }.getOrNull()
}
