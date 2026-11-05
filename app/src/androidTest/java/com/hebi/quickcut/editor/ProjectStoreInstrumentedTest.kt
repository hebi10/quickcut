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
    @Test fun deletingOneSavedProjectPreservesAnotherProjectAndItsTimeline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProjectStore(context)
        val firstSource = VideoClip(
            id = System.nanoTime(),
            uri = "content://com.hebi.quickcut/first-project-test",
            sourceDurationMs = 6_000L,
        )
        val secondSource = VideoClip(
            id = firstSource.id + 1L,
            uri = "content://com.hebi.quickcut/second-project-test",
            sourceDurationMs = 7_000L,
        )
        val first = store.save(
            EditorSessionSnapshot(EditTimeline(listOf(firstSource)), firstSource.id, 500L, true),
            "첫 번째 프로젝트",
        )
        val second = store.save(
            EditorSessionSnapshot(EditTimeline(listOf(secondSource)), secondSource.id, 750L, false),
            "두 번째 프로젝트",
        )
        try {
            store.delete(first.id)
            assertNull(store.load(first.id))
            val remaining = checkNotNull(store.load(second.id))
            assertEquals(secondSource.uri, remaining.timeline.clips.single().uri)
            assertEquals(750L, remaining.playPositionMs)
            assertNotNull(store.list().firstOrNull { it.id == second.id })
        } finally {
            store.delete(first.id)
            store.delete(second.id)
        }
    }

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
