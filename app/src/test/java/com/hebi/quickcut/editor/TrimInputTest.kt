package com.hebi.quickcut.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrimInputTest {
    @Test
    fun removedEdgesAcceptExactlyOneHundredMilliseconds() {
        assertEquals(TrimRange(4_900L, 5_000L),
            TrimInput.removedRange("4.9", "0", 5_000L))
        assertEquals(TrimRange(4_800L, 4_900L),
            TrimInput.removedRange("4.8", "0.1", 5_000L))
    }

    @Test
    fun removedEdgesRejectLessThanOneHundredMilliseconds() {
        assertNull(TrimInput.removedRange("4.901", "0", 5_000L))
    }

    @Test
    fun detailedRangeUsesTheSameInclusiveBoundary() {
        assertEquals(TrimRange(4_900L, 5_000L),
            TrimInput.selectedRange("4.9", "5", 5_000L))
        assertNull(TrimInput.selectedRange("4.901", "5", 5_000L))
    }

    @Test
    fun malformedOrNonFiniteInputHasNoRange() {
        listOf("", "-", "-1", "NaN", "Infinity", "1,2.3").forEach { invalid ->
            assertNull("Invalid leading input: $invalid",
                TrimInput.removedRange(invalid, "0", 5_000L))
            assertNull("Invalid detailed input: $invalid",
                TrimInput.selectedRange(invalid, "5", 5_000L))
        }
    }

    @Test
    fun rangeCannotExtendOutsideTheSource() {
        assertNull(TrimInput.selectedRange("0", "5.001", 5_000L))
        assertNull(TrimInput.selectedRange("3", "2", 5_000L))
        assertNull(TrimInput.removedRange("5", "1", 5_000L))
    }

    @Test
    fun decimalCommaAndWhitespaceAreAccepted() {
        assertEquals(1_250L, TrimInput.millisValue(" 1,25 "))
    }

    @Test
    fun roundsToTheNearestMillisecondWithoutFloatingPointLoss() {
        assertEquals(1_001L, TrimInput.millisValue("1.0005"))
        assertEquals(1_000L, TrimInput.millisValue("1.0004"))
        assertNull(TrimInput.millisValue("-0.0001"))
    }

    @Test
    fun overflowingTimesCannotWrapIntoAnApparentlyValidRange() {
        assertNull(TrimInput.millisValue("9223372036854775.808"))
        assertNull(TrimInput.removedRange("9223372036854775.807",
            "9223372036854775.807", 5_000L))
    }

    @Test
    fun longSourcesKeepTheirFullRangeAvailableForDetailedEditing() {
        assertEquals(TrimRange(0L, 172_800_000L),
            TrimInput.selectedRange("0", "172800", 172_800_000L))
        assertEquals(Long.MAX_VALUE, TrimInput.millisValue("9223372036854775.807"))
    }

    @Test
    fun formattingPreservesWholeMilliseconds() {
        assertEquals("0", TrimInput.secondsText(0L))
        assertEquals("4.9", TrimInput.secondsText(4_900L))
        assertEquals("1234.567", TrimInput.secondsText(1_234_567L))
        assertEquals("9223372036854775.807", TrimInput.secondsText(Long.MAX_VALUE))
    }
}
