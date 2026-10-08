package com.hebi.quickcut.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class OriginalActionTest {
    @Test fun exportedSourcesAreUniqueEvenWhenClipIsSplit() {
        val a = VideoClip(id=1, uri="content://media/external/video/media/1", sourceDurationMs=4000)
        val a2 = a.copy(id=2, startMs=2000, endMs=4000)
        val b = a.copy(id=3, uri="content://media/external/video/media/2")
        assertEquals(listOf(a.uri,b.uri), uniqueExportSources(EditTimeline(listOf(a,a2,b))))
    }
    @Test fun deletedClipsAreNotScheduledForOriginalDeletion() {
        val a = VideoClip(id=1, uri="content://media/external/video/media/1", sourceDurationMs=4000)
        val b = a.copy(id=2, uri="content://media/external/video/media/2")
        val edited = EditTimeline(listOf(a,b)).delete(a.id)
        assertEquals(listOf(b.uri), uniqueExportSources(edited))
    }
    @Test fun defaultActionDoesNotDelete() {
        assertEquals(OriginalAction.KEEP, OriginalAction.values().first())
    }
}
