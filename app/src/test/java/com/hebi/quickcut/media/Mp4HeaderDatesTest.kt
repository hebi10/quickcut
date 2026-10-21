package com.hebi.quickcut.media

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Mp4HeaderDatesTest {
    private val date = 1_609_459_200_000L
    private fun box(name: String, body: ByteArray, extended: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(if (extended) 1 else body.size + 8); it.writeBytes(name)
            if (extended) it.writeLong(body.size + 16L)
            it.write(body)
        } }.toByteArray()
    private fun header(name: String, version: Int = 0): ByteArray = box(name,
        ByteArray(if (version == 0) 100 else 112) { 0x35 }.apply {
            this[0] = version.toByte(); this[1] = 0; this[2] = 0; this[3] = 0
        })
    private fun fixture(version: Int = 0): ByteArray =
        box("ftyp", "isom0000isom".toByteArray()) +
            box("mdat", ByteArray(64) { it.toByte() }) +
            box("moov", header("mvhd", version) +
                box("trak", header("tkhd", version) + box("mdia", header("mdhd", version))) +
                box("trak", header("tkhd", version) + box("mdia", header("mdhd", version))), extended = true)
    private fun withFile(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("quickcut-dates", ".mp4")
        try { file.writeBytes(bytes); block(file) } finally { file.delete() }
    }
    private fun offsets(bytes: ByteArray): List<Int> = (4 until bytes.size - 4).filter {
        String(bytes, it, 4, Charsets.ISO_8859_1) in setOf("mvhd", "tkhd", "mdhd")
    }.map { it + 8 }

    @Test fun updatesAllDatesWithoutChangingMediaOrOtherHeaderBytes() = withFile(fixture()) { file ->
        val expected = file.readBytes()
        offsets(expected).forEach { at ->
            ByteBuffer.wrap(expected).putInt(at, (date / 1000 + 2_082_844_800L).toInt())
                .putInt(at + 4, (date / 1000 + 2_082_844_800L).toInt())
        }
        Mp4HeaderDates.update(file, date)
        assertArrayEquals(expected, file.readBytes())
    }

    @Test fun supportsVersionOneAndExtendedSizeContainers() = withFile(fixture(1)) { file ->
        val future = 2_366_841_600_000L
        val expected = file.readBytes()
        offsets(expected).forEach { at -> ByteBuffer.wrap(expected)
            .putLong(at, future / 1000 + 2_082_844_800L)
            .putLong(at + 8, future / 1000 + 2_082_844_800L) }
        Mp4HeaderDates.update(file, future)
        assertArrayEquals(expected, file.readBytes())
    }

    @Test fun malformedNestedBoxIsRejectedBeforeAnyWrite() {
        val bytes = fixture() + box("moov", header("mvhd") +
            box("trak", byteArrayOf(0, 0, 0, 100, 116, 107, 104, 100)))
        withFile(bytes) { file ->
            assertThrows(java.io.IOException::class.java) { Mp4HeaderDates.update(file, date) }
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun unrepresentableVersionZeroDateIsRejectedBeforeAnyWrite() = withFile(fixture()) { file ->
        val before = file.readBytes()
        assertThrows(java.io.IOException::class.java) { Mp4HeaderDates.update(file, 2_366_841_600_000L) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun absentOrUnsupportedHeadersAreRejected() {
        for (bytes in listOf(box("mdat", ByteArray(8)), fixture(2),
            box("moov", box("mvhd", ByteArray(4))))) {
            withFile(bytes) { file ->
                assertThrows(java.io.IOException::class.java) { Mp4HeaderDates.update(file, date) }
                assertArrayEquals(bytes, file.readBytes())
            }
        }
    }
}
