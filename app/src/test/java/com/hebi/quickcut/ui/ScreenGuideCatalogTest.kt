package com.hebi.quickcut.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenGuideCatalogTest {
    @Test
    fun everyScreenHasAStableVersionedAccessibleGuide() {
        val guides = QuickCutGuides.all
        assertEquals(14, guides.size)
        assertEquals(guides.size, guides.map { it.id }.distinct().size)
        guides.forEach { guide ->
            assertTrue(guide.id.matches(Regex("[a-z]+")))
            assertTrue(guide.version > 0)
            assertTrue(guide.title.isNotBlank())
            assertTrue(guide.introduction.isNotBlank())
            assertTrue(guide.steps.size in 2..3)
            assertTrue(guide.steps.all { it.title.isNotBlank() && it.description.isNotBlank() })
            assertEquals(guide, QuickCutGuides.find(guide.id))
        }
        assertEquals(null, QuickCutGuides.find("unavailable"))
    }

    @Test
    fun updatedGuidesDescribePerFileTrimAndBothFastSaveDirections() {
        val batch = requireNotNull(QuickCutGuides.find("batch"))
        val quick = requireNotNull(QuickCutGuides.find("quick"))
        val save = requireNotNull(QuickCutGuides.find("save"))
        val tools = requireNotNull(QuickCutGuides.find("tools"))
        assertEquals(2, batch.version)
        assertEquals(2, quick.version)
        assertEquals(2, save.version)
        assertEquals(2, tools.version)
        assertTrue(batch.steps.any { "앞뒤 시간 조정" in it.description && "공통 시간 사용" in it.description })
        assertTrue(quick.steps.any { "키프레임" in it.description })
        assertTrue(quick.steps.any { "최대 1초" in it.description && "원본은 유지" in it.description })
        assertTrue(save.steps.any { "덜 자르고 고속 저장" in it.description })
        assertTrue(tools.steps.any { "영상별 시간" in it.description })
        assertEquals(1, requireNotNull(QuickCutGuides.find("projects")).version)
    }

    @Test
    fun guidesDistinguishLocalProjectsAndVideoExports() {
        val project = requireNotNull(QuickCutGuides.find("projects"))
        val export = requireNotNull(QuickCutGuides.find("save"))
        assertTrue(project.steps.any { "원본" in it.description })
        assertTrue(export.steps.any { "MP4" in it.description })
        assertTrue(requireNotNull(QuickCutGuides.find("recovery")).steps
            .any { "다시 확인" in it.description })
    }
}
