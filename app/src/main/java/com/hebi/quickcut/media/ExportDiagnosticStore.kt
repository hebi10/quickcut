package com.hebi.quickcut.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.util.UUID
import java.io.IOException
import kotlinx.coroutines.CancellationException
import com.hebi.quickcut.editor.EditTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded reports stay on this device. Diagnostic persistence never gates export. */
internal object ExportDiagnosticStore {
    private const val PREFERENCES = "export_diagnostics"
    private const val HISTORY_KEY = "history_v1"

    fun latest(context: Context): String? = history(context).recent.firstOrNull()?.text

    @Synchronized fun history(context: Context): ExportDiagnosticHistory = runCatching {
        ExportDiagnosticCodec.decode(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(HISTORY_KEY, null))
    }.getOrDefault(ExportDiagnosticHistory())

    fun record(context: Context, operationId: String): ExportDiagnosticRecord? = history(context).record(operationId)

    fun begin(context: Context, timeline: EditTimeline): ExportDiagnosticSession {
        @Suppress("DEPRECATION")
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val facts = ExportDiagnosticFacts(version, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT,
            timeline.clips.map { it.uri }.distinct().size, timeline.clips.firstOrNull()?.sourceDurationMs ?: 0L,
            runCatching { timeline.totalDurationUs }.getOrDefault(0L), timeline.clips.size)
        return ExportDiagnosticSession(context.applicationContext, facts).also { it.stage(ExportStage.PREPARING) }
    }

    @Synchronized internal fun save(context: Context, record: ExportDiagnosticRecord) {
        runCatching {
            val updated = history(context).updated(record)
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(HISTORY_KEY, ExportDiagnosticCodec.encode(updated))
                .remove("latest").apply()
        }
    }
}

internal class ExportDiagnosticSession(private val context: Context, private var facts: ExportDiagnosticFacts) {
    val operationId: String = UUID.randomUUID().toString()
    private val timing = ExportStageTiming(SystemClock::elapsedRealtime)
    private var currentStage = ExportStage.PREPARING
    private var reasons = ExportDiagnostics()
    private var method: String? = null
    private var completed = false
    @Synchronized fun stage(stage: ExportStage) {
        if (completed) return
        timing.stage(stage); currentStage = stage; persist()
    }
    @Synchronized fun reasons(value: ExportDiagnostics) { if (!completed) { reasons = value; persist() } }
    @Synchronized fun report(value: ExportReport) { if (!completed) method = value.method.name }
    @Synchronized fun finish(status: String, error: Throwable? = null) {
        if (completed) return
        completed = true
        timing.finish()
        val outcome = when (status) {
            "완료" -> ExportDiagnosticStatus.COMPLETE
            "취소" -> ExportDiagnosticStatus.CANCELLED
            else -> ExportDiagnosticStatus.FAILED
        }
        // Never retain arbitrary exception subclasses, messages, causes, or stack traces.
        val failureType = when (error) {
            null -> null
            is ReencodeRequiredException -> "ReencodeRequiredException"
            is CancellationException -> "CancellationException"
            is SecurityException -> "SecurityException"
            is IllegalArgumentException -> "IllegalArgumentException"
            is IllegalStateException -> "IllegalStateException"
            is IOException -> "IOException"
            else -> "Exception"
        }
        persist(outcome, failureType)
    }

    suspend fun inspect(timeline: EditTimeline) = withContext(Dispatchers.IO) {
        val uri = timeline.clips.firstOrNull()?.uri?.let(Uri::parse) ?: return@withContext
        val extractor = MediaExtractor()
        try {
            val bytes = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                it.length.takeIf { value -> value >= 0 } ?: it.parcelFileDescriptor.statSize.takeIf { value -> value >= 0 }
            } }.getOrNull()
            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            runCatching {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (video == null && mime.startsWith("video/")) video = format
                    if (audio == null && mime.startsWith("audio/")) audio = format
                }
            }
            fun MediaFormat?.integer(key: String): Int? = runCatching { this?.getInteger(key) }.getOrNull()
            val fps = runCatching { video?.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                ?: video.integer(MediaFormat.KEY_FRAME_RATE)
            @Suppress("DEPRECATION")
            val gallery = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes }.getOrNull()
            synchronized(this@ExportDiagnosticSession) {
                facts = facts.copy(sourceBytes = bytes,
                    videoMime = runCatching { video?.getString(MediaFormat.KEY_MIME) }.getOrNull(),
                    width = video.integer(MediaFormat.KEY_WIDTH), height = video.integer(MediaFormat.KEY_HEIGHT), frameRate = fps,
                    colorStandard = video.integer(MediaFormat.KEY_COLOR_STANDARD), colorTransfer = video.integer(MediaFormat.KEY_COLOR_TRANSFER),
                    audioMime = runCatching { audio?.getString(MediaFormat.KEY_MIME) }.getOrNull(),
                    audioChannels = audio.integer(MediaFormat.KEY_CHANNEL_COUNT), audioSampleRate = audio.integer(MediaFormat.KEY_SAMPLE_RATE),
                    cacheAvailableBytes = runCatching { StatFs(context.cacheDir.absolutePath).availableBytes }.getOrNull(),
                    galleryAvailableBytes = gallery)
                persist()
            }
        } finally { runCatching { extractor.release() } }
    }

    private fun persist(status: ExportDiagnosticStatus = ExportDiagnosticStatus.RUNNING, failureType: String? = null) {
        val times = timing.snapshot()
        ExportDiagnosticStore.save(context, ExportDiagnosticRecord(operationId, status, currentStage,
            times.elapsedMs, times.stageMs, facts.sanitized(), reasons, method, failureType))
    }
}
