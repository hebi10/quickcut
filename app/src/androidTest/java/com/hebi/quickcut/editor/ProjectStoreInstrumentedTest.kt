package com.hebi.quickcut.editor

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        verifyCorruptIndexCannotHideOrReplaceExistingProject(context)
        verifyCorruptNamedProjectCannotBeOverwritten(context)
    }

    private fun verifyCorruptIndexCannotHideOrReplaceExistingProject(context: Context) {
        val preferencesName = "quickcut_index_guard_test_" + System.nanoTime()
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val store = ProjectStore(context, preferencesName)
        val clip = VideoClip(1L, "content://fixture/index-guard", 3_000L)
        val snapshot = EditorSessionSnapshot(EditTimeline(listOf(clip)), clip.id, 0L, false)
        val project = store.save(snapshot, "기록 보호 확인")
        val originalIndex = checkNotNull(preferences.getString("index", null))
        try {
            assertTrue(preferences.edit().putString("index", "{broken").commit())
            assertThrows(ProjectIndexReadException::class.java) { store.list() }
            assertThrows(ProjectIndexReadException::class.java) { store.save(snapshot, "덮어쓰기 차단") }
            assertThrows(ProjectIndexReadException::class.java) { store.delete(project.id) }
            assertEquals("{broken", preferences.getString("index", null))
            assertTrue(preferences.edit().putString("index", originalIndex).commit())
            assertEquals(snapshot, store.load(project.id))
        } finally {
            preferences.edit().putString("index", originalIndex).commit()
            store.delete(project.id)
            preferences.edit().clear().commit()
        }
    }

    private fun verifyCorruptNamedProjectCannotBeOverwritten(context: Context) {
        val preferencesName = "quickcut_named_guard_test_" + System.nanoTime()
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val store = ProjectStore(context, preferencesName)
        val clip = VideoClip(2L, "content://fixture/session-guard", 3_000L)
        val snapshot = EditorSessionSnapshot(EditTimeline(listOf(clip)), clip.id, 300L, false)
        val project = store.save(snapshot, "저장 데이터 보호")
        val projectPrefs = context.getSharedPreferences("quickcut_project_" + project.id, Context.MODE_PRIVATE)
        val originalRaw = checkNotNull(projectPrefs.getString("session", null))
        val originalIndex = preferences.getString("index", null)
        try {
            assertTrue(projectPrefs.edit().putString("session", "{broken").commit())
            assertThrows(EditorSessionReadException::class.java) { store.load(project.id) }
            assertThrows(EditorSessionReadException::class.java) {
                store.save(snapshot.copy(playPositionMs = 800L), "저장 데이터 보호", project.id)
            }
            assertThrows(EditorSessionReadException::class.java) { store.delete(project.id) }
            assertEquals("{broken", projectPrefs.getString("session", null))
            assertEquals(originalIndex, preferences.getString("index", null))
        } finally {
            projectPrefs.edit().putString("session", originalRaw).commit()
            store.delete(project.id)
            preferences.edit().clear().commit()
        }
    }
}
