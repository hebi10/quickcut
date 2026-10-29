package com.hebi.quickcut.media

import java.nio.ByteBuffer

/** A sync flag alone does not exclude leading pictures (AVC open GOP / HEVC CRA). */
internal object FastTrimBoundary {
    // Eligibility and tail lookahead are resource bounded. Exceeding either
    // budget declines the framework path; it never establishes a safe fence.
    const val MAX_PROBE_BYTES = 64L * 1024L * 1024L
    const val MAX_PROBE_MS = 250L

    class Budget(private val startedMs: Long) {
        private var bytes = 0L

        fun withinTime(nowMs: Long): Boolean = nowMs - startedMs <= MAX_PROBE_MS

        fun include(sampleBytes: Long, nowMs: Long): Boolean {
            if (sampleBytes <= 0L || sampleBytes > MAX_PROBE_BYTES - bytes ||
                !withinTime(nowMs)) return false
            bytes += sampleBytes
            return true
        }
    }

    /** Accept only IDR pictures which forbid subsequent leading pictures. */
    fun isClosedRandomAccess(data: ByteBuffer, mime: String): Boolean {
        if (mime != "video/avc" && mime != "video/hevc") return false
        val start = data.position()
        val limit = data.limit()
        fun byte(at: Int) = data.get(at).toInt() and 0xff
        fun startCode(at: Int): Int = when {
            at + 3 <= limit && byte(at) == 0 && byte(at + 1) == 0 && byte(at + 2) == 1 -> 3
            at + 4 <= limit && byte(at) == 0 && byte(at + 1) == 0 &&
                byte(at + 2) == 0 && byte(at + 3) == 1 -> 4
            else -> 0
        }
        val annexB = startCode(start) != 0
        var at = start
        var foundPicture = false
        while (at < limit) {
            val nalStart: Int
            val nalEnd: Int
            if (annexB) {
                val prefix = startCode(at)
                if (prefix == 0) return false
                nalStart = at + prefix
                var next = nalStart
                while (next < limit && startCode(next) == 0) next++
                nalEnd = next
                at = next
            } else {
                // MediaExtractor normally exposes Annex B. Also accept complete
                // four-byte length-prefixed units; other layouts decline safely.
                if (limit - at < 4) return false
                val length = (byte(at).toLong() shl 24) or (byte(at + 1).toLong() shl 16) or
                    (byte(at + 2).toLong() shl 8) or byte(at + 3).toLong()
                if (length <= 0L || length > limit - at - 4L) return false
                nalStart = at + 4
                nalEnd = nalStart + length.toInt()
                at = nalEnd
            }
            if (nalEnd - nalStart < 2 || byte(nalStart) and 0x80 != 0) return false
            if (mime == "video/avc") {
                val type = byte(nalStart) and 0x1f
                if (type == 0 || type > 13) return false
                if (type in 1..5) {
                    if (type != 5) return false
                    foundPicture = true
                }
            } else {
                if (byte(nalStart + 1) and 7 == 0) return false
                val type = (byte(nalStart) shr 1) and 0x3f
                if (type <= 31) {
                    // IDR_W_RADL (19), CRA (21) and BLA may have leading
                    // pictures. Only IDR_N_LP (20) is a proof by itself.
                    if (type != 20 || nalEnd - nalStart < 3) return false
                    foundPicture = true
                }
            }
        }
        return foundPicture
    }
}
