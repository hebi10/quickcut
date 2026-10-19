package com.hebi.quickcut.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchTimeInputTest {
    @Test fun storedMillisecondsRoundTripThroughVisibleMinuteAndSecondFields() {
        for (millis in listOf(0L, 1L, 1_001L, 59_999L, 60_000L, 65_500L, 7_200_000L)) {
            val parts = BatchTimeInput.parts(com.hebi.quickcut.editor.TrimInput.secondsText(millis))
            assertEquals(millis, BatchTimeInput.parse(parts.minutes + ":" + parts.seconds))
            assertNull(BatchTimeInput.error(parts))
        }
    }

    @Test fun minutesAndDecimalSecondsHaveExactMillisecondValues() {
        assertEquals(65_500L, BatchTimeInput.parse("1:05.5"))
        assertEquals(1_001L, BatchTimeInput.parse("0:1.001"))
        assertEquals(125_250L, BatchTimeInput.parse("2:5,25"))
        assertEquals(500L, BatchTimeInput.parse("0:.5"))
        assertEquals(500L, BatchTimeInput.parse(",5"))
    }

    @Test fun existingSecondDraftsDoNotLoseMilliseconds() {
        assertEquals(1_001L, BatchTimeInput.parse("1.001"))
        assertEquals(65_500L, BatchTimeInput.parse("65.5"))
        assertEquals(7_200_000L, BatchTimeInput.parse("7200"))
    }

    @Test fun emptyComponentMeansZeroWhileEditingButAnEmptyDurationIsInvalid() {
        assertEquals(3_000L, BatchTimeInput.parse(":3"))
        assertEquals(60_000L, BatchTimeInput.parse("1:"))
        assertNull(BatchTimeInput.parse(":"))
        assertNull(BatchTimeInput.parse(""))
    }

    @Test fun invalidAndOutOfRangeInputIsNotSilentlyClamped() {
        listOf("1:60", "0:60.001", "120:0.001", "121:0", "7200.001",
            "-1:3", "1:-3", "0:NaN", "1e3", "0:1.0001", "1:2:3",
            "999999999999999999999999999999999999999").forEach {
            assertNull("Must reject $it", BatchTimeInput.parse(it))
        }
        assertEquals(7_200_000L, BatchTimeInput.parse("120:0"))
        assertEquals(59_999L, BatchTimeInput.parse("0:59.999"))
    }
}
