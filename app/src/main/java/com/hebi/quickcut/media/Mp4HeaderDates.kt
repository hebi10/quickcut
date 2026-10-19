package com.hebi.quickcut.media

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Edits only header dates in a private, completed MP4; never moves media bytes. */
internal object Mp4HeaderDates {
    private data class Field(val offset: Long, val wide: Boolean)

    fun update(file: File, epochMs: Long) {
        val seconds = Math.floorDiv(epochMs, 1_000L) + 2_082_844_800L
        if (seconds < 0L) throw IOException("MP4 날짜가 지원 범위를 벗어났습니다.")
        RandomAccessFile(file, "rw").use { output ->
            val fields = mutableListOf<Field>()
            var boxes = 0
            fun inspect(start: Long, end: Long, parent: String): Map<String, Int> {
                val counts = mutableMapOf<String, Int>()
                var at = start
                while (at < end) {
                    if (++boxes > 100_000 || end - at < 8L) throw IOException("잘못된 MP4 헤더입니다.")
                    output.seek(at)
                    val size32 = output.readInt().toLong() and 0xffff_ffffL
                    val type = ByteArray(4).also(output::readFully).toString(Charsets.ISO_8859_1)
                    val headerSize = if (size32 == 1L) 16L else 8L
                    if (end - at < headerSize) throw IOException("잘린 MP4 헤더입니다.")
                    val size = when (size32) {
                        0L -> end - at
                        1L -> output.readLong()
                        else -> size32
                    }
                    if (size < headerSize || size > end - at) throw IOException("잘못된 MP4 크기입니다.")
                    val payload = at + headerSize
                    counts[type] = counts.getOrDefault(type, 0) + 1
                    val nested = when {
                        parent == "root" && type == "moov" -> "moov"
                        parent == "moov" && type == "trak" -> "trak"
                        parent == "trak" && type == "mdia" -> "mdia"
                        else -> null
                    }
                    if (nested != null) {
                        val children = inspect(payload, at + size, nested)
                        val required = when (nested) {
                            "moov" -> listOf("mvhd")
                            "trak" -> listOf("tkhd", "mdia")
                            else -> listOf("mdhd")
                        }
                        if (required.any { children[it] != 1 } ||
                            (nested == "moov" && children.getOrDefault("trak", 0) < 1)
                        ) throw IOException("필수 MP4 날짜 헤더가 없습니다.")
                    } else if ((parent == "moov" && type == "mvhd") ||
                        (parent == "trak" && type == "tkhd") || (parent == "mdia" && type == "mdhd")) {
                        if (size - headerSize < 4L) throw IOException("잘린 MP4 날짜 헤더입니다.")
                        output.seek(payload)
                        val version = output.readUnsignedByte()
                        if (version !in 0..1) throw IOException("지원하지 않는 MP4 날짜 버전입니다.")
                        val width = if (version == 1) 8 else 4
                        if (size - headerSize < 4L + width * 2 ||
                            (width == 4 && seconds > 0xffff_ffffL)
                        ) throw IOException("MP4 날짜를 안전하게 기록할 수 없습니다.")
                        fields += Field(payload + 4L, width == 8)
                    }
                    at += size
                }
                return counts
            }
            val top = inspect(0L, output.length(), "root")
            if (top["moov"] != 1 || fields.isEmpty()) throw IOException("MP4 날짜 헤더를 찾을 수 없습니다.")
            // All bounds, versions and dates are checked before the first mutation.
            for (field in fields) {
                output.seek(field.offset)
                repeat(2) { if (field.wide) output.writeLong(seconds) else output.writeInt(seconds.toInt()) }
            }
            output.fd.sync()
        }
    }
}
