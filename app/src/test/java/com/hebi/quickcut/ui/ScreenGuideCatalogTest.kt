package com.hebi.quickcut.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenGuideCatalogTest {
    @Test
    fun everyScreenHasAStableVersionedAccessibleGuide() {
        val guides = QuickCutGuides.all
        assertEquals(13, guides.size)
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
    fun guidesDistinguishLocalProjectsAndVideoExports() {
        val project = requireNotNull(QuickCutGuides.find("projects"))
        val export = requireNotNull(QuickCutGuides.find("save"))
        assertTrue(project.steps.any { "원본" in it.description })
        assertTrue(export.steps.any { "MP4" in it.description })
        assertTrue(requireNotNull(QuickCutGuides.find("recovery")).steps
            .any { "다시 확인" in it.description })
    }
}
