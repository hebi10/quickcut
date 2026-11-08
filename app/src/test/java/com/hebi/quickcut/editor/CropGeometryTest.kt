package com.hebi.quickcut.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropGeometryTest {
    private fun assertClose(expected: Float, actual: Float) {
        assertEquals(expected, actual, 0.0015f)
    }

    @Test fun verticalCropUsesTheFullHeightWithoutDistortingLandscapeSource() {
        val region = CropGeometry.centerForRatio(1920, 1080, CropRatio.VERTICAL)
        assertClose(0f, region.top)
        assertClose(1f, region.bottom)
        assertClose(9f / 16f, region.width * 1920f / (region.height * 1080f))
        assertClose(0.5f, (region.left + region.right) / 2f)
    }

    @Test fun squareAndHorizontalPresetsPreserveSourceFrameRatio() {
        val square = CropGeometry.centerForRatio(320, 240, CropRatio.SQUARE)
        assertClose(1f, square.width * 320f / (square.height * 240f))
        val wide = CropGeometry.centerForRatio(320, 240, CropRatio.HORIZONTAL)
        assertClose(16f / 9f, wide.width * 320f / (wide.height * 240f))
        assertEquals(CropRegion.FULL, CropGeometry.centerForRatio(320, 240, CropRatio.ORIGINAL))
    }

    @Test fun moveAndZoomStayInsideSourceFrame() {
        val square = CropGeometry.centerForRatio(320, 240, CropRatio.SQUARE)
        val moved = square.move(100f, -100f)
        assertClose(1f, moved.right)
        assertClose(0f, moved.top)
        val zoomed = moved.zoom(2f)
        assertTrue(zoomed.width < square.width)
        assertTrue(zoomed.height < square.height)
        assertTrue(zoomed.left >= 0f && zoomed.top >= 0f)
        assertTrue(zoomed.right <= 1f && zoomed.bottom <= 1f)
        val expanded = zoomed.zoom(0.01f)
        assertTrue(expanded.right <= 1f && expanded.bottom <= 1f)
    }

    @Test fun freeResizeClampsEdgesAndKeepsValidRegion() {
        val region = CropRegion(0.3f, 0.2f, 0.7f, 0.8f)
        val resized = region.resize(0.9f, 0.95f)
        assertTrue(resized.left >= 0f && resized.top >= 0f)
        assertTrue(resized.right <= 1f && resized.bottom <= 1f)
        assertClose(0.9f, resized.width)
        assertClose(0.95f, resized.height)
    }

    @Test fun normalizedCropMapsToMedia3NdcCoordinates() {
        val ndc = CropGeometry.ndc(CropRegion(0.25f, 0.125f, 0.75f, 0.875f))
        assertClose(-0.5f, ndc[0])
        assertClose(0.5f, ndc[1])
        assertClose(-0.75f, ndc[2])
        assertClose(0.75f, ndc[3])
    }

    @Test fun timelineCropIsImmutableAndRemovable() {
        val base = EditTimeline(listOf(VideoClip(1, "test.mp4", 10_000)))
        val crop = CropGeometry.centerForRatio(1920, 1080, CropRatio.SQUARE)
        val edited = base.updateCrop(1, crop)
        assertNull(base.clips.single().crop)
        assertEquals(crop, edited.clips.single().crop)
        assertFalse(edited.clips.single().crop!!.isWholeFrame)
        assertNull(edited.updateCrop(1, CropRegion.FULL).clips.single().crop)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRectCannotEscapeBounds() {
        CropRegion(0.8f, 0f, 1.1f, 1f)
    }
}
