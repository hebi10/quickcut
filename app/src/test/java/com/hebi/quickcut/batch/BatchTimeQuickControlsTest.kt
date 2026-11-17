package com.hebi.quickcut.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTimeQuickControlsTest {
    @Test fun quickPresetUpdatesOnlyTheActiveInputs() {
        assertEquals(BatchTimeValues("0:5", "0:5"),
            BatchTimeQuickControls.preset(BatchClipMode.REMOVE_EDGES, "1:2", "0:9", 5))
        assertEquals(BatchTimeValues("0:3", "0:9"),
            BatchTimeQuickControls.preset(BatchClipMode.KEEP_FIRST, "1:2", "0:9", 3))
        assertEquals(BatchTimeValues("1:2", "0:10"),
            BatchTimeQuickControls.preset(BatchClipMode.KEEP_LAST, "1:2", "0:9", 10))
        assertEquals(BatchTimeValues("0:1", "0:1"),
            BatchTimeQuickControls.preset(BatchClipMode.JOIN_EDGES, "1:2", "0:9", 1))
    }

    @Test fun selectedPresetDerivesFromCurrentManualAndTemplateValues() {
        assertTrue(BatchTimeQuickControls.selected(BatchClipMode.REMOVE_EDGES, "0:3", "3", 3))
        assertFalse(BatchTimeQuickControls.selected(BatchClipMode.REMOVE_EDGES, "0:3", "0:5", 3))
        assertTrue(BatchTimeQuickControls.selected(BatchClipMode.KEEP_FIRST, "0:5", "invalid", 5))
        assertFalse(BatchTimeQuickControls.selected(BatchClipMode.KEEP_LAST, "0:5", "invalid", 5))
    }

    @Test fun oneSecondStepsPreservePreciseFractionalSecondsAndMinuteCarry() {
        assertEquals("1:6.5", BatchTimeQuickControls.step("1:5.5", 1))
        assertEquals("0:59.001", BatchTimeQuickControls.step("1:0.001", -1))
        assertEquals("0:0", BatchTimeQuickControls.step("0:0.5", -1))
        assertEquals("1:0", BatchTimeQuickControls.step("0:59", 1))
    }

    @Test fun boundariesAndInvalidTypingCannotBeSilentlyReplaced() {
        assertNull(BatchTimeQuickControls.step("0:0", -1))
        assertNull(BatchTimeQuickControls.step("120:0", 1))
        assertEquals("120:0", BatchTimeQuickControls.step("119:59.5", 1))
        assertNull(BatchTimeQuickControls.step("0:60", -1))
        assertNull(BatchTimeQuickControls.step("not-a-number", 1))
    }
}
