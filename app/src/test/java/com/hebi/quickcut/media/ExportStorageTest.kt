package com.hebi.quickcut.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportStorageTest {
    @Test
    fun directCurrentDateExportAllocatesOnlyOneGalleryFile() {
        val required = ExportStoragePolicy.requirements(100_000_000L, directWrite = true, sharedVolume = true)
        assertEquals(0L, required.cacheBytes)
        assertEquals(116_777_216L, required.galleryBytes)
        assertEquals(required.galleryBytes, required.sharedBytes)
    }

    @Test
    fun originalDateOrTransformNeedsTwoFilesOnSharedStorage() {
        val required = ExportStoragePolicy.requirements(100_000_000L, directWrite = false, sharedVolume = true)
        assertEquals(116_777_216L, required.cacheBytes)
        assertEquals(116_777_216L, required.galleryBytes)
        assertEquals(216_777_216L, required.sharedBytes)
    }

    @Test
    fun separatelySufficientCacheAndGallerySpaceCanStillBeInsufficientOnOneVolume() {
        val required = ExportStoragePolicy.requirements(100_000_000L, directWrite = false, sharedVolume = true)
        assertFalse(required.fits(150_000_000L, 150_000_000L))
        assertTrue(required.fits(216_777_216L, 216_777_216L))
    }

    @Test
    fun independentVolumesAreCheckedSeparately() {
        val required = ExportStoragePolicy.requirements(100_000_000L, directWrite = false, sharedVolume = false)
        assertTrue(required.fits(150_000_000L, 150_000_000L))
        assertFalse(required.fits(116_777_215L, 150_000_000L))
        assertFalse(required.fits(150_000_000L, 116_777_215L))
    }

    @Test
    fun estimatesOnlyTheRetainedSourceDurationAndAllowsTranscodeGrowth() {
        val source = ExportSourceSize(100_000_000L, 10_000L, 2_000L)
        assertEquals(22_000_000L, ExportStoragePolicy.estimateOutputBytes(listOf(source), directWrite = true))
        assertEquals(30_000_000L, ExportStoragePolicy.estimateOutputBytes(listOf(source), directWrite = false))
    }

    @Test
    fun repeatedSegmentsAllocateTheirRepeatedMediaBytes() {
        val source = ExportSourceSize(100_000_000L, 10_000L, 2_000L)
        assertEquals(44_000_000L, ExportStoragePolicy.estimateOutputBytes(listOf(source, source), directWrite = true))
    }

    @Test
    fun unknownProviderSizeUsesDurationInsteadOfAssumingZeroBytes() {
        val source = ExportSourceSize(null, 10_000L, 2_000L)
        assertEquals(3_300_000L, ExportStoragePolicy.estimateOutputBytes(listOf(source), directWrite = true))
    }

    @Test
    fun oversizedEstimatesCannotWrapToNegativeOrFitAvailableSpace() {
        val source = ExportSourceSize(Long.MAX_VALUE, 1_000L, 1_000L)
        assertEquals(Long.MAX_VALUE, ExportStoragePolicy.estimateOutputBytes(listOf(source), directWrite = false))
        val required = ExportStoragePolicy.requirements(Long.MAX_VALUE, directWrite = false, sharedVolume = true)
        assertEquals(Long.MAX_VALUE, required.sharedBytes)
        assertFalse(required.fits(1_000_000_000L, 1_000_000_000L))
    }
}
