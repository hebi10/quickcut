package com.hebi.quickcut.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4TimestampData
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.hebi.quickcut.editor.EditTimeline
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class, ExperimentalApi::class)
class VideoExporter(private val context: Context) {
    suspend fun export(
        timeline: EditTimeline,
        onProgress: (Int?) -> Unit,
        onFinalizing: () -> Unit,
        saveDateMode: SaveDateMode = SaveDateMode.CURRENT,
        outputBaseName: String? = null,
    ): android.net.Uri {
        require(!timeline.isEmpty) { "편집할 영상이 없습니다." }
        val originalDate = if (saveDateMode == SaveDateMode.ORIGINAL) {
            withContext(Dispatchers.IO) {
                OriginalMediaDates.resolve(context, Uri.parse(timeline.clips.first().uri))
                    ?: throw IOException(
                        "원본에 확인 가능한 촬영/수정 날짜가 없어 원본 날짜로 저장할 수 없습니다."
                    )
            }
        } else null
        val outputTimestampMs = originalDate?.epochMs ?: System.currentTimeMillis()

        // Fast path: a single unmodified MP4 clip with a safe sync-sample boundary.
        // Keep the original-date export on Transformer to preserve embedded MP4 dates.
        if (saveDateMode == SaveDateMode.CURRENT && timeline.clips.size == 1) {
            val clip = timeline.clips.first()
            val plan = withContext(Dispatchers.IO) {
                FastMp4Trimmer.prepare(context, clip)
            }
            if (plan != null) {
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    val output = GalleryWriter(context).saveDirect(
                        outputBaseName = outputBaseName,
                        expectedDurationMs = clip.durationMs,
                        expectAudio = !clip.muted && clip.hasAudio,
                        writer = { destination ->
                            FastMp4Trimmer.write(context, plan, destination, onProgress)
                        },
                        onFinalizing = onFinalizing,
                    )
                    Log.i(
                        "QuickCutExport",
                        "method=fast_remux elapsedMs=" +
                            (SystemClock.elapsedRealtime() - startedAt) +
                            " startShiftUs=" + (plan.startUs - clip.startMs * 1_000L),
                    )
                    return output
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (unsupported: FastTrimUnavailableException) {
                    // GalleryWriter removes the failed pending output before retrying.
                    Log.w("QuickCutExport", "Fast remux unsupported; using Media3", unsupported)
                } catch (unsupported: IllegalStateException) {
                    Log.w("QuickCutExport", "Native remux unavailable; using Media3", unsupported)
                } catch (unsupported: IllegalArgumentException) {
                    Log.w("QuickCutExport", "Native remux rejected input; using Media3", unsupported)
                }
            }
        }

        val temporary = File.createTempFile("quickcut_", ".mp4", context.cacheDir)
        try {
            check(temporary.delete()) { "임시 출력 파일을 준비하지 못했습니다." }
            withContext(Dispatchers.Main.immediate) {
                transform(timeline, temporary, onProgress, outputTimestampMs)
            }
            withContext(Dispatchers.IO) {
                MediaReader.validateExport(temporary)
            }
            onFinalizing()
            val exportedUri = GalleryWriter(context).save(
                temporary,
                originalTimestampMs = originalDate?.epochMs,
                outputBaseName = outputBaseName,
            )
            try {
                // A registered URI must be readable and contain a playable video before
                // optional original deletion can even be offered in the success screen.
                withContext(Dispatchers.IO) {
                    MediaReader.metadata(context, exportedUri)
                    if (originalDate != null) {
                        val verifiedDate = OriginalMediaDates.resolve(context, exportedUri)?.epochMs
                        if (verifiedDate == null || abs(verifiedDate - originalDate.epochMs) > 3_000L) {
                            throw IOException(
                                "기기 갤러리에 원본 날짜를 적용하지 못했습니다. 저장을 취소했습니다."
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.delete(exportedUri, null, null) }
                }
                throw error
            }
            return exportedUri
        } finally {
            temporary.delete()
        }
    }

    private suspend fun transform(
        timeline: EditTimeline,
        file: File,
        onProgress: (Int?) -> Unit,
        outputTimestampMs: Long,
    ): Unit = suspendCancellableCoroutine { continuation ->
        val main = Handler(Looper.getMainLooper())
        val progress = ProgressHolder()
        lateinit var transformer: Transformer
        val poller = object : Runnable {
            override fun run() {
                if (!continuation.isActive) return
                try {
                    when (transformer.getProgress(progress)) {
                        Transformer.PROGRESS_STATE_AVAILABLE -> onProgress(progress.progress)
                        else -> onProgress(null)
                    }
                } catch (_: Exception) {
                    onProgress(null)
                }
                main.postDelayed(this, 250L)
            }
        }
        transformer = Transformer.Builder(context)
            .apply {
                val timestampSeconds = Mp4TimestampData.unixTimeToMp4TimeSeconds(
                    outputTimestampMs
                )
                setMuxerFactory(
                    InAppMp4Muxer.Factory(
                        InAppMp4Muxer.MetadataProvider { entries ->
                            entries.removeAll { it is Mp4TimestampData }
                            entries.add(Mp4TimestampData(timestampSeconds, timestampSeconds))
                        }
                    )
                )
            }
            .experimentalSetTrimOptimizationEnabled(timeline.clips.size == 1)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    main.removeCallbacks(poller)
                    Log.i(
                        "QuickCutExport",
                        "method=media3 optimization=" + exportResult.optimizationResult +
                            " videoProcess=" + exportResult.videoConversionProcess +
                            " audioProcess=" + exportResult.audioConversionProcess,
                    )
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    main.removeCallbacks(poller)
                    if (continuation.isActive) continuation.resumeWithException(exportException)
                }
            })
            .build()

        continuation.invokeOnCancellation {
            val stop = Runnable {
                main.removeCallbacks(poller)
                transformer.cancel()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) stop.run()
            else main.post(stop)
        }

        try {
            if (timeline.clips.size == 1) {
                transformer.start(CompositionFactory.editedClip(timeline.clips.first()), file.absolutePath)
            } else {
                transformer.start(CompositionFactory.from(timeline), file.absolutePath)
            }
            main.post(poller)
        } catch (error: Exception) {
            main.removeCallbacks(poller)
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}
