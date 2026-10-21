package com.hebi.quickcut.media

import android.content.Context
import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.hebi.quickcut.editor.EditTimeline
import java.io.File
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.util.UUID

/** Failure evidence only. Never changes an assertion, an export, or the failure thrown by a test. */
internal object SmartCutFailureDiagnostics {
    fun capture(context: Context, name: String, timeline: EditTimeline, output: Uri?, failure: Throwable) {
        try {
            val root = checkNotNull(context.getExternalFilesDir(null))
            val directory = File(root, "native-diagnostics/${name}_${UUID.randomUUID()}")
            check(directory.mkdirs())
            File(directory, "diagnostics.txt").printWriter().use { log ->
                log.println("failure=$failure\ntimeline=$timeline\noutput=$output")
                failure.printStackTrace(log)
                val inputs = timeline.clips.mapIndexed { index, clip ->
                    Triple("source-$index", Uri.parse(clip.uri), clip.startMs * 1_000L)
                } + listOfNotNull(output?.let { Triple("output", it, 0L) })
                // Preserve every MP4 before any optional decoder operation can fail.
                inputs.forEach { (label, uri, _) ->
                    attempt(log, "copy $label") {
                        checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            File(directory, "$label.mp4").outputStream().use { input.copyTo(it) }
                        }
                    }
                }
                inputs.forEach { (label, uri, requestedUs) ->
                    log.println("\n=== $label uri=$uri requestedUs=$requestedUs ===")
                    attempt(log, "extractor") { extractor(context, uri, log) }
                    attempt(log, "sequential default decoder") { sequential(context, uri, requestedUs, null, log) }
                    val software = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull {
                        !it.isEncoder && it.name == "c2.android.avc.decoder"
                    }
                    if (software != null) attempt(log, "sequential software decoder") {
                        sequential(context, uri, requestedUs, software.name, log)
                    }
                    attempt(log, "MMR OPTION_CLOSEST") { retriever(context, uri, requestedUs, log) }
                }
            }
            Log.e("SmartCutDiagnostics", "Saved failure evidence: ${directory.absolutePath}")
        } catch (diagnosticFailure: Throwable) {
            // A diagnostic failure must never replace the original test failure.
            failure.addSuppressed(diagnosticFailure)
            Log.e("SmartCutDiagnostics", "Could not finish failure evidence", diagnosticFailure)
        }
    }

    private inline fun attempt(log: PrintWriter, operation: String, block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            log.println("$operation failed: $error")
            error.printStackTrace(log)
        } finally { log.flush() }
    }

    private fun extractor(context: Context, uri: Uri, log: PrintWriter) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var video = -1
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                log.println("track[$track]=$format")
                for (csd in listOf("csd-0", "csd-1", "csd-2")) {
                    format.getByteBuffer(csd)?.let { log.println("track[$track].$csd=${hex(it)}") }
                }
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) video = track
            }
            check(video >= 0)
            extractor.selectTrack(video)
            var index = 0
            while (extractor.sampleTrackIndex >= 0 && index < 160) {
                log.println("sample[$index] pts=${extractor.sampleTime} flags=${extractor.sampleFlags} size=${extractor.sampleSize}")
                index++
                if (!extractor.advance()) break
            }
        } finally { extractor.release() }
    }

    private fun sequential(context: Context, uri: Uri, requestedUs: Long, decoderName: String?, log: PrintWriter) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(requestedUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            log.println("sequential requested=$requestedUs seekPts=${extractor.sampleTime} decoder=$decoderName")
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val codec = if (decoderName == null) MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                else MediaCodec.createByCodecName(decoderName)
            decoder = codec
            log.println("decoder.name=${codec.name} input=$format")
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var frames = 0
            var inputCount = 0
            var lastPts = Long.MIN_VALUE
            val deadline = SystemClock.elapsedRealtime() + 15_000L
            while (!outputEnded && frames < 180 && lastPts < requestedUs + 1_200_000L &&
                SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(5_000L)
                    if (index >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(index))
                        buffer.clear()
                        val size = if (extractor.sampleTrackIndex >= 0) extractor.readSampleData(buffer, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            log.println("queue[$inputCount] pts=${extractor.sampleTime} flags=${extractor.sampleFlags} size=$size")
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            inputCount++
                            extractor.advance()
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 5_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> log.println("decoder.outputFormat=${codec.outputFormat}")
                    else -> if (index >= 0) {
                        try {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                val pixels = runCatching {
                                    codec.getOutputImage(index)?.use { image -> imageColors(image) } ?: "no Image"
                                }.getOrElse { "Image error: $it" }
                                log.println("frame[$frames] pts=${info.presentationTimeUs} flags=${info.flags} size=${info.size} colors=$pixels")
                                lastPts = info.presentationTimeUs
                                frames++
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
            log.println("sequential done frames=$frames lastPts=$lastPts eos=$outputEnded inputEnded=$inputEnded deadline=${SystemClock.elapsedRealtime() >= deadline}")
        } finally {
            try { decoder?.release() } finally { extractor.release() }
        }
    }

    private fun imageColors(image: Image): String {
        check(image.planes.size == 3) { "format=${image.format} planes=${image.planes.size}" }
        val crop = image.cropRect
        fun value(plane: Int, x: Int, y: Int): Int {
            val p = image.planes[plane]
            return p.buffer.get(p.buffer.position() + y * p.rowStride + x * p.pixelStride).toInt() and 255
        }
        val colors = listOf(0.25, 0.5, 0.75).flatMap { fy ->
            listOf(0.25, 0.5, 0.75).map { fx ->
                val x = crop.left + (crop.width() * fx).toInt()
                val y = crop.top + (crop.height() * fy).toInt()
                val yy = value(0, x, y)
                val u = value(1, x / 2, y / 2)
                val v = value(2, x / 2, y / 2)
                val c = yy - 16
                val d = u - 128
                val e = v - 128
                val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                "yuv($yy,$u,$v)/rgb($r,$g,$b)"
            }
        }
        return "${image.width}x${image.height} crop=$crop grid=$colors"
    }

    private fun retriever(context: Context, uri: Uri, timeUs: Long, log: PrintWriter) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            log.println("MMR duration=${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)}")
            val bitmap = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 160, 120)
            log.println("MMR time=$timeUs bitmap=${bitmap?.width}x${bitmap?.height}")
            bitmap?.let {
                try {
                    val color = it.getPixel(it.width / 2, it.height / 2)
                    log.println("MMR rgb=${Color.red(color)},${Color.green(color)},${Color.blue(color)}")
                } finally { it.recycle() }
            }
        } finally { retriever.release() }
    }

    private fun hex(buffer: ByteBuffer): String {
        val copy = buffer.duplicate()
        val bytes = ByteArray(copy.remaining().coerceAtMost(4096))
        copy.get(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
