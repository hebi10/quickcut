package com.hebi.quickcut.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.hebi.quickcut.editor.EditTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One bounded, local report. SharedPreferences atomically replaces the previous operation. */
internal object ExportDiagnosticStore {
    private const val PREFERENCES = "export_diagnostics"
    private const val KEY = "latest"
    fun latest(context: Context): String? = runCatching {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null)?.take(8_192)
    }.getOrNull()

    fun begin(context: Context, timeline: EditTimeline): ExportDiagnosticSession {
        @Suppress("DEPRECATION")
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val facts = ExportDiagnosticFacts(version, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT,
            timeline.clips.map { it.uri }.distinct().size, timeline.clips.firstOrNull()?.sourceDurationMs ?: 0L,
            runCatching { timeline.totalDurationUs }.getOrDefault(0L), timeline.clips.size)
        return ExportDiagnosticSession(context.applicationContext, facts).also { it.stage(ExportStage.PREPARING) }
    }

    internal fun save(context: Context, text: String) {
        runCatching { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, text.take(8_192)).apply() }
    }
}

internal class ExportDiagnosticSession(private val context: Context, private var facts: ExportDiagnosticFacts) {
    private val startedAt = SystemClock.elapsedRealtime()
    private var currentStage = ExportStage.PREPARING
    private var reasons = ExportDiagnostics()
    private var method: String? = null
    @Synchronized fun stage(stage: ExportStage) { currentStage = stage; persist("처리 중") }
    @Synchronized fun reasons(value: ExportDiagnostics) { reasons = value; persist("처리 중") }
    @Synchronized fun report(value: ExportReport) { method = value.method.name }
    @Synchronized fun finish(status: String, error: Throwable? = null) { persist(status, error?.javaClass?.simpleName) }

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
                persist("처리 중")
            }
        } finally { runCatching { extractor.release() } }
    }

    private fun persist(status: String, failureType: String? = null) {
        ExportDiagnosticStore.save(context, facts.text(currentStage, status,
            SystemClock.elapsedRealtime() - startedAt, reasons, method, failureType))
    }
}
