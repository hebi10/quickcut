package com.hebi.quickcut.media

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class FastTrimBoundaryTest {
    @Test fun avcIdrCanFenceTheDiscardedTail() {
        assertTrue(FastTrimBoundary.isClosedRandomAccess(packet(0x65, 0x80), "video/avc"))
        assertFalse(FastTrimBoundary.isClosedRandomAccess(packet(0x41, 0x80), "video/avc"))
    }

    @Test fun hevcCraAndIdrWithLeadingPicturesCannotFenceTheTail() {
        assertTrue(FastTrimBoundary.isClosedRandomAccess(packet(20 shl 1, 1, 0x80), "video/hevc"))
        assertFalse(FastTrimBoundary.isClosedRandomAccess(packet(19 shl 1, 1, 0x80), "video/hevc"))
        assertFalse(FastTrimBoundary.isClosedRandomAccess(packet(21 shl 1, 1, 0x80), "video/hevc"))
    }

    @Test fun unknownMalformedAndMixedAccessUnitsFailClosed() {
        assertFalse(FastTrimBoundary.isClosedRandomAccess(ByteBuffer.wrap(byteArrayOf(0x65)), "video/avc"))
        assertFalse(FastTrimBoundary.isClosedRandomAccess(packet(0x67, 0x80), "video/avc"))
        assertFalse(FastTrimBoundary.isClosedRandomAccess(ByteBuffer.wrap(
            packet(0x65, 0x80).array() + packet(0x41, 0x80).array()), "video/avc"))
        assertFalse(FastTrimBoundary.isClosedRandomAccess(packet(0x65, 0x80), "video/unknown"))
    }

    @Test fun lengthPrefixedAccessUnitsAndThreeByteStartCodesAreRecognized() {
        assertTrue(FastTrimBoundary.isClosedRandomAccess(
            ByteBuffer.wrap(byteArrayOf(0, 0, 0, 2, 0x65, 0x80.toByte())), "video/avc"))
        assertTrue(FastTrimBoundary.isClosedRandomAccess(
            ByteBuffer.wrap(byteArrayOf(0, 0, 1, 0x65, 0x80.toByte())), "video/avc"))
        assertFalse(FastTrimBoundary.isClosedRandomAccess(
            ByteBuffer.wrap(byteArrayOf(0, 0, 0, 3, 0x65, 0x80.toByte())), "video/avc"))
    }

    @Test fun boundaryProofHasAByteAndElapsedTimeBudget() {
        val bytes = FastTrimBoundary.Budget(0L)
        assertTrue(bytes.include(1L, 0L))
        assertFalse(bytes.include(FastTrimBoundary.MAX_PROBE_BYTES, 0L))
        assertFalse(FastTrimBoundary.Budget(0L).include(1L, FastTrimBoundary.MAX_PROBE_MS + 1L))
        assertFalse(FastTrimBoundary.Budget(0L).include(0L, 0L))
    }

    @Test fun missingClosedFenceCannotCauseUnlimitedDiscardedTailInspection() {
        val budget = FastTrimBoundary.Budget(0L)
        val megabyte = 1024L * 1024L
        repeat(64) { assertTrue(budget.include(megabyte, 0L)) }
        assertFalse("Without an IDR fence or EOS the next bytes must decline the copy path",
            budget.include(1L, 0L))
    }

    private fun packet(vararg bytes: Int): ByteBuffer =
        ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + bytes.map { it.toByte() }.toByteArray())
}
