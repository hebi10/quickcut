package com.hebi.quickcut.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectStoreInstrumentedTest {
    @Test fun namedProjectsPersistAndDeleteWithoutTouchingOtherSessions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projects = ProjectStore(context)
        val source = VideoClip(
            id = System.nanoTime(),
            uri = "content://com.hebi.quickcut/project-test-unique",
            sourceDurationMs = 4_000L,
        )
        val snapshot = EditorSessionSnapshot(
            timeline = EditTimeline(listOf(source)),
            selectedId = source.id,
            playPositionMs = 500L,
            quickMode = true,
        )
        val name = "프로젝트 테스트 ${System.nanoTime()}"
        val created = projects.save(snapshot, name)
        try {
            assertNotNull(projects.list().firstOrNull { it.id == created.id })
            projects.setActiveId(created.id)
            assertEquals(created.id, ProjectStore(context).activeId())
            assertEquals(snapshot, projects.load(created.id))
            val updated = projects.save(snapshot.copy(playPositionMs = 700L), "수정됨", created.id)
            assertEquals(created.id, updated.id)
            assertEquals(700L, projects.load(created.id)?.playPositionMs)
        } finally {
            projects.delete(created.id)
        }
        assertFalse(projects.list().any { it.id == created.id })
        assertNull(projects.load(created.id))
        assertNull(projects.activeId())
    }
}
