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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
        exportMode: ExportMode = ExportMode.ALLOW_REENCODE,
        onOutputCreated: (Uri) -> Unit = {},
        onCommitted: (Uri) -> Unit = {},
        resolvedOriginalDate: OriginalTimestamp? = null,
        onReport: (ExportReport) -> Unit = {},
    ): android.net.Uri {
        require(!timeline.isEmpty) { "편집할 영상이 없습니다." }
        require(timeline.clips.all { it.durationMs >= 100L }) { "저장할 영상 구간은 0.1초 이상이어야 합니다." }
        val startedAt = SystemClock.elapsedRealtime()
        val expectedDurationMs = timeline.totalDurationMs
        val expectAudio = timeline.clips.any { !it.muted && it.hasAudio }
        val originalDate = if (saveDateMode == SaveDateMode.ORIGINAL) {
            withContext(Dispatchers.IO) {
                resolvedOriginalDate ?: OriginalMediaDates.resolve(context, Uri.parse(timeline.clips.first().uri))
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
                val output = try {
                    GalleryWriter(context).saveDirect(
                        outputBaseName = outputBaseName,
                        expectedDurationMs = clip.durationMs,
                        expectAudio = !clip.muted && clip.hasAudio,
                        writer = { destination ->
                            FastMp4Trimmer.write(context, plan, destination, onProgress)
                        },
                        onFinalizing = onFinalizing,
                        estimatedOutputBytes = withContext(Dispatchers.IO) {
                            ExportStorage.estimateOutputBytes(context, timeline, directWrite = true)
                        },
                        onOutputCreated = onOutputCreated,
                        onCommitted = onCommitted,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (unsupported: FastTrimUnavailableException) {
                    if (exportMode == ExportMode.FAST_ONLY) {
                        throw ReencodeRequiredException("고속 복사를 완료할 수 없습니다. 정확한 위치로 자동 저장을 선택해 주세요.", unsupported)
                    }
                    Log.w("QuickCutExport", "Fast remux unsupported; using Media3", unsupported)
                    null
                }
                if (output != null) {
                    Log.i(
                        "QuickCutExport",
                        "method=fast_remux elapsedMs=" +
                            (SystemClock.elapsedRealtime() - startedAt) +
                            " startShiftUs=" + (plan.startUs - clip.startMs * 1_000L),
                    )
                    // Publication already succeeded. Reporting must never enter the
                    // remux-fallback catch and start another export of the same clip.
                    reportPublished(
                        ExportReport(ExportMethod.ORIGINAL_COPY, SystemClock.elapsedRealtime() - startedAt),
                        onReport,
                    )
                    return output
                }
            }
        }

        if (exportMode == ExportMode.FAST_ONLY) {
            throw ReencodeRequiredException(
                "현재 자르기 위치 또는 저장 옵션에서는 고속 복사를 사용할 수 없습니다. 정확한 위치로 자동 저장을 선택해 주세요."
            )
        }
        withContext(Dispatchers.IO) {
            val estimate = ExportStorage.estimateOutputBytes(context, timeline, directWrite = false)
            ExportStorage.requireSpace(context, estimate, directWrite = false)
        }
        val optimizeTrim = timeline.clips.singleOrNull()?.let { clip ->
            withContext(Dispatchers.IO) { ExactTrimOptimization.canOptimize(context, clip) }
        } ?: false
        // Merely choose a unique path here. Creating a temp file in a cancellable
        // withContext can lose the cleanup handle while dispatching back to Main.
        val temporary = File(context.cacheDir, "quickcut_" + UUID.randomUUID().toString() + ".mp4")
        try {
            val result = withContext(Dispatchers.Main.immediate) {
                transform(timeline, temporary, onProgress, outputTimestampMs, optimizeTrim)
            }
            withContext(Dispatchers.IO) {
                MediaReader.validateExport(temporary, expectedDurationMs, expectAudio, originalDate?.epochMs)
            }
            val output = GalleryWriter(context).save(
                temporary,
                originalTimestampMs = originalDate?.epochMs,
                outputBaseName = outputBaseName,
                expectedDurationMs = expectedDurationMs,
                expectAudio = expectAudio,
                onFinalizing = onFinalizing,
                onOutputCreated = onOutputCreated,
                onCommitted = onCommitted,
            )
            reportPublished(
                ExportReport(
                    ExportReportMapper.method(result.videoConversionProcess, result.audioConversionProcess),
                    SystemClock.elapsedRealtime() - startedAt,
                    result.optimizationResult,
                ),
                onReport,
            )
            return output
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { temporary.delete() }
            }
        }
    }

    private suspend fun transform(
        timeline: EditTimeline,
        file: File,
        onProgress: (Int?) -> Unit,
        outputTimestampMs: Long,
        optimizeTrim: Boolean,
    ): ExportResult = suspendCancellableCoroutine { continuation ->
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
            .experimentalSetTrimOptimizationEnabled(optimizeTrim)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    main.removeCallbacks(poller)
                    Log.i(
                        "QuickCutExport",
                        "method=media3 optimization=" + exportResult.optimizationResult +
                            " videoProcess=" + exportResult.videoConversionProcess +
                            " audioProcess=" + exportResult.audioConversionProcess,
                    )
                    if (continuation.isActive) continuation.resume(exportResult)
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

    private suspend fun reportPublished(report: ExportReport, consumer: (ExportReport) -> Unit) {
        // Keep the outer context's dispatcher so cancellation while returning from
        // Main cannot discard a URI which GalleryWriter has already committed.
        withContext(NonCancellable) {
            withContext(Dispatchers.Main.immediate) {
                try {
                    consumer(report)
                } catch (error: Throwable) {
                    if (error is VirtualMachineError || error is ThreadDeath || error is LinkageError) throw error
                    Log.w("QuickCutExport", "Published export report consumer failed", error)
                }
            }
        }
    }
}

class ReencodeRequiredException(message: String, cause: Throwable? = null) : IOException(message, cause)


