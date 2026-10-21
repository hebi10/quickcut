package com.hebi.quickcut.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.ParcelFileDescriptor
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
        onDiagnostics: (ExportDiagnostics) -> Unit = {},
        onStage: (ExportStage) -> Unit = {},
    ): Uri {
        val diagnostic = ExportDiagnosticStore.begin(context, timeline)
        val main = Handler(Looper.getMainLooper())
        val stage: (ExportStage) -> Unit = { value ->
            diagnostic.stage(value)
            val update = Runnable { runCatching { onStage(value) } }
            if (Looper.myLooper() == Looper.getMainLooper()) update.run() else main.post(update)
        }
        try {
            stage(ExportStage.PREPARING)
            try { diagnostic.inspect(timeline) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional diagnostics must not prevent an export. */ }
            val output = exportInternal(timeline, onProgress,
                onFinalizing = { stage(ExportStage.PUBLISHING); onFinalizing() },
                saveDateMode, outputBaseName, exportMode, onOutputCreated, onCommitted, resolvedOriginalDate,
                onReport = { diagnostic.report(it); onReport(it) },
                onDiagnostics = { diagnostic.reasons(it); onDiagnostics(it) }, onStage = stage)
            stage(ExportStage.COMPLETE)
            diagnostic.finish("완료")
            return output
        } catch (cancelled: CancellationException) {
            diagnostic.finish("취소", cancelled)
            throw cancelled
        } catch (error: Exception) {
            diagnostic.finish("실패", error)
            throw error
        }
    }

    private suspend fun exportInternal(
        timeline: EditTimeline,
        onProgress: (Int?) -> Unit,
        onFinalizing: () -> Unit,
        saveDateMode: SaveDateMode,
        outputBaseName: String?,
        exportMode: ExportMode,
        onOutputCreated: (Uri) -> Unit,
        onCommitted: (Uri) -> Unit,
        resolvedOriginalDate: OriginalTimestamp?,
        onReport: (ExportReport) -> Unit,
        onDiagnostics: (ExportDiagnostics) -> Unit,
        onStage: (ExportStage) -> Unit,
    ): android.net.Uri {
        require(!timeline.isEmpty) { "편집할 영상이 없습니다." }
        require(timeline.clips.all { it.durationUs >= 100_000L }) { "저장할 영상 구간은 0.1초 이상이어야 합니다." }
        val startedAt = SystemClock.elapsedRealtime()
        var diagnostics = ExportDiagnostics()
        suspend fun showDiagnostics() {
            withContext(Dispatchers.Main.immediate) {
                try { onDiagnostics(diagnostics) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Log.w("QuickCutExport", "Could not display processing reasons", error) }
            }
        }
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
        // Original dates change MP4 headers, not compressed pictures.
        onStage(ExportStage.ANALYZING)
        if (timeline.clips.size == 1) {
            val clip = timeline.clips.first()
            val inspection = withContext(Dispatchers.IO) {
                FastMp4Trimmer.inspect(context, clip)
            }
            val plan = inspection.plan
            if (plan == null) {
                diagnostics = diagnostics.copy(copyReason =
                    FastExportReason.fromCopyReasonName(inspection.reason?.name) ?: FastExportReason.UNKNOWN)
                showDiagnostics()
            }
            if (plan != null) {
                onStage(ExportStage.COPYING)
                val output = try {
                    if (originalDate != null) {
                        saveDatedCopy(timeline, plan, originalDate.epochMs, outputBaseName,
                            onProgress, onFinalizing, onOutputCreated, onCommitted, onStage)
                    } else GalleryWriter(context).saveDirect(
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
                        onValidating = { onStage(ExportStage.VALIDATING) },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (unsupported: FastTrimUnavailableException) {
                    diagnostics = diagnostics.copy(copyReason = FastExportReason.COPY_FAILED)
                    showDiagnostics()
                    Log.w("QuickCutExport", "Fast remux unsupported; using Media3", unsupported)
                    null
                }
                if (output != null) {
                    Log.i(
                        "QuickCutExport",
                        "method=fast_remux elapsedMs=" +
                            (SystemClock.elapsedRealtime() - startedAt) +
                            " startShiftUs=" + (plan.startUs - clip.startUs),
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
        } else {
            diagnostics = diagnostics.copy(copyReason = FastExportReason.MULTIPLE_CLIPS)
            showDiagnostics()
        }

        withContext(Dispatchers.IO) {
            val estimate = ExportStorage.estimateOutputBytes(context, timeline, directWrite = false)
            ExportStorage.requireSpace(context, estimate, directWrite = false)
        }
        // Merely choose a unique path here. Creating a temp file in a cancellable
        // withContext can lose the cleanup handle while dispatching back to Main.
        val temporary = File(context.cacheDir, "quickcut_" + UUID.randomUUID().toString() + ".mp4")
        try {
            var nativeReason: FastExportReason? = null
            onStage(ExportStage.PROCESSING)
            val nativeResult = try {
                    NativeSmartCutTrimmer.tryExport(context, timeline, temporary, onProgress,
                        onUnavailable = { nativeReason = it },
                        copyVideoOnly = exportMode == ExportMode.FAST_ONLY)
                        ?.also {
                            onStage(ExportStage.VALIDATING)
                            withContext(Dispatchers.IO) {
                                originalDate?.let { Mp4HeaderDates.update(temporary, it.epochMs) }
                                MediaReader.validateExport(temporary, expectedDurationMs, expectAudio, originalDate?.epochMs)
                            }
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (invalidOutput: IOException) {
                    Log.w("QuickCutExport", "Native output validation failed; using exact Media3 export",
                        invalidOutput)
                    nativeReason = FastExportReason.OUTPUT_VALIDATION
                    null
                }
            val media3Result = if (nativeResult == null) {
                diagnostics = diagnostics.copy(smartCutReason = nativeReason ?: FastExportReason.UNKNOWN)
                showDiagnostics()
                if (exportMode == ExportMode.FAST_ONLY) {
                    throw ReencodeRequiredException(
                        "고속 저장을 완료하지 못했습니다. 선택한 구간을 유지하며 정확한 위치로 자동 저장을 선택할 수 있습니다.")
                }
                withContext(Dispatchers.IO) {
                    if (temporary.exists() && !temporary.delete()) {
                        throw IOException("이전 저장 시도의 임시 파일을 정리할 수 없습니다.")
                    }
                }
                val optimizeTrim = timeline.clips.singleOrNull()?.let { clip ->
                    withContext(Dispatchers.IO) { ExactTrimOptimization.canOptimize(context, clip) }
                } ?: false
                onStage(ExportStage.PROCESSING)
                withContext(Dispatchers.Main.immediate) {
                    transform(timeline, temporary, onProgress, outputTimestampMs, optimizeTrim)
                }
            } else {
                Log.i("QuickCutExport", "method=native_smart_cut copiedVideoPackets=" +
                    nativeResult.copiedPackets + " encodedVideoPackets=" + nativeResult.encodedPackets)
                null
            }
            onStage(ExportStage.VALIDATING)
            withContext(Dispatchers.IO) {
                MediaReader.validateExport(temporary, expectedDurationMs, expectAudio, originalDate?.epochMs)
            }
            onStage(ExportStage.SAVING)
            val output = GalleryWriter(context).save(
                temporary,
                originalTimestampMs = originalDate?.epochMs,
                outputBaseName = outputBaseName,
                expectedDurationMs = expectedDurationMs,
                expectAudio = expectAudio,
                onFinalizing = onFinalizing,
                onOutputCreated = onOutputCreated,
                onCommitted = onCommitted,
                onValidating = { onStage(ExportStage.VALIDATING) },
            )
            reportPublished(
                ExportReport(
                    if (nativeResult != null) {
                        when {
                            nativeResult.encodedPackets > 0 -> ExportMethod.PARTIAL_REENCODE
                            expectAudio -> ExportMethod.AUDIO_REENCODE
                            else -> ExportMethod.ORIGINAL_COPY
                        }
                    } else ExportReportMapper.method(
                        checkNotNull(media3Result).videoConversionProcess,
                        media3Result.audioConversionProcess,
                    ),
                    SystemClock.elapsedRealtime() - startedAt,
                    media3Result?.optimizationResult,
                    diagnostics,
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

    /** Stage dated copies privately: malformed headers can never reach the gallery. */
    private suspend fun saveDatedCopy(
        timeline: EditTimeline,
        plan: FastMp4Trimmer.Plan,
        timestampMs: Long,
        outputBaseName: String?,
        onProgress: (Int?) -> Unit,
        onFinalizing: () -> Unit,
        onOutputCreated: (Uri) -> Unit,
        onCommitted: (Uri) -> Unit,
        onStage: (ExportStage) -> Unit,
    ): Uri {
        val file = File(context.cacheDir, "quickcut_date_" + UUID.randomUUID() + ".mp4")
        val expectAudio = timeline.clips.any { it.hasAudio && !it.muted }
        try {
            withContext(Dispatchers.IO) {
                val estimate = ExportStorage.estimateOutputBytes(context, timeline, directWrite = false)
                ExportStorage.requireSpace(context, estimate, directWrite = false)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                    FastMp4Trimmer.write(context, plan, it.fileDescriptor, onProgress)
                }
                try {
                    onStage(ExportStage.VALIDATING)
                    Mp4HeaderDates.update(file, timestampMs)
                    MediaReader.validateExport(file, timeline.totalDurationMs, expectAudio, timestampMs)
                } catch (invalid: IOException) {
                    throw FastTrimUnavailableException("Dated copy validation failed", invalid)
                }
            }
            // Publication is outside the fallback-producing validation block.
            onStage(ExportStage.SAVING)
            return GalleryWriter(context).save(file, timestampMs, outputBaseName,
                timeline.totalDurationMs, expectAudio, onFinalizing, onOutputCreated, onCommitted,
                onValidating = { onStage(ExportStage.VALIDATING) })
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
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


