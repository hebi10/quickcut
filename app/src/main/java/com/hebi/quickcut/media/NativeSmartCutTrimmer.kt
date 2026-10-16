package com.hebi.quickcut.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.Keep
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal data class NativeSmartCutResult(val copiedPackets: Long, val encodedPackets: Long)

/** A failed attempt owns only its cache file, never a published gallery item. */
internal object NativeSmartCutTrimmer {
    private val available: Boolean by lazy {
        try {
            System.loadLibrary("quickcut_smartcut")
            true
        } catch (error: UnsatisfiedLinkError) {
            Log.w("QuickCutExport", "Native smart cut is unavailable on this device", error)
            false
        }
    }

    suspend fun tryExport(
        context: Context,
        clip: VideoClip,
        destination: File,
        onProgress: (Int?) -> Unit,
    ): NativeSmartCutResult? = withContext(Dispatchers.IO) {
        val jobContext = currentCoroutineContext()
        jobContext.ensureActive()
        if (!available) return@withContext null
        try {
            // Keep this handle alive until the blocking native call returns. Native
            // code recognizes the fd identifier and uses duplicated descriptors with
            // independent pread cursors, without reopening a provider's private path.
            context.contentResolver.openAssetFileDescriptor(Uri.parse(clip.uri), "r")?.use { asset ->
                val descriptor = asset.parcelFileDescriptor
                val size = descriptor.statSize
                if (asset.startOffset != 0L || size <= 0L ||
                    (asset.declaredLength >= 0L && asset.declaredLength != size)
                ) return@use null
                val position = Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
                Os.lseek(descriptor.fileDescriptor, position, OsConstants.SEEK_SET)
                jobContext.ensureActive()
                val callbacks = NativeSmartCutCallbacks(jobContext, onProgress)
                val result = try {
                    NativeSmartCutBridge.cut(
                        "/proc/self/fd/${descriptor.fd}", destination.absolutePath,
                        clip.startMs * 1_000L, clip.endMs * 1_000L, clip.muted, callbacks,
                    )
                } finally {
                    callbacks.close()
                }
                // Cancellation is checked after native cleanup, before any fallback
                // or publication. Never abandon a native writer on a detached thread.
                jobContext.ensureActive()
                when {
                    result.size != 3 -> null
                    result[0] == -1L -> throw CancellationException("Native smart cut cancelled")
                    result[0] != 1L || result[1] <= 0L || result[2] <= 0L -> null
                    else -> NativeSmartCutResult(result[1], result[2])
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            jobContext.ensureActive()
            Log.w("QuickCutExport", "Native smart cut declined; keeping the exact Media3 path", error)
            null
        }
    }
}

@Keep
internal object NativeSmartCutBridge {
    @JvmStatic
    external fun cut(
        inputPath: String,
        outputPath: String,
        startUs: Long,
        endUs: Long,
        muted: Boolean,
        callbacks: NativeSmartCutCallbacks,
    ): LongArray
}

/** JNI calls these on its worker thread; UI progress remains on Main. */
@Keep
internal class NativeSmartCutCallbacks(
    private val jobContext: CoroutineContext,
    private val consumer: (Int?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private var lastProgress = -1
    private var lastPostedAt = 0L

    fun isCancelled(): Boolean = closed || !jobContext.isActive

    fun onProgress(percent: Int) {
        if (isCancelled()) return
        val progress = percent.coerceIn(0, 99)
        val now = SystemClock.uptimeMillis()
        if (progress == lastProgress || (lastProgress >= 0 && now - lastPostedAt < 200L)) return
        lastProgress = progress
        lastPostedAt = now
        main.postAtTime({
            if (!isCancelled()) consumer(progress)
        }, this, now)
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(this)
    }
}
