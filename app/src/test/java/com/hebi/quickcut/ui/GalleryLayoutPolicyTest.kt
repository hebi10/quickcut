package com.hebi.quickcut.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryLayoutPolicyTest {
    @Test fun largeFontUsesOneColumnOnNarrowScreens() {
        assertTrue(GalleryLayoutPolicy.singleColumn(320f, 1.6f))
        assertTrue(GalleryLayoutPolicy.singleColumn(360f, 2f))
        assertFalse(GalleryLayoutPolicy.singleColumn(400f, 1.6f))
        assertFalse(GalleryLayoutPolicy.singleColumn(360f, 1.5f))
        assertFalse(GalleryLayoutPolicy.singleColumn(600f, 2f))
    }

    @Test fun selectionDescriptionPreservesFullNameAndKnownDuration() {
        val title = "2026_가족여행_첫째날_오후_고화질_세로영상_원본.mp4"
        val label = GalleryLayoutPolicy.selectionDescription(title, "길이 01:23", true, 2)
        assertTrue(label.contains(title))
        assertTrue(label.contains("길이 01:23"))
        assertTrue(label.contains("선택됨, 2번째 영상"))
        assertTrue(GalleryLayoutPolicy.selectionDescription(title, "길이 확인 불가", false, 0)
            .endsWith("선택 안 됨"))
    }
}
