package com.hebi.quickcut.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginalMediaDatesTest {
    @Test fun parsesIsoCreationTime() {
        assertEquals(1_609_459_200_000L, OriginalMediaDates.parseMp4Date("2021-01-01T00:00:00Z"))
    }
    @Test fun parsesCompactMp4CreationTime() {
        assertEquals(1_609_459_200_000L, OriginalMediaDates.parseMp4Date("20210101T000000.000Z"))
    }
    @Test fun rejectsUnknownAndInvalidDates() {
        assertNull(OriginalMediaDates.parseMp4Date(null))
        assertNull(OriginalMediaDates.parseMp4Date("no timestamp"))
        assertNull(OriginalMediaDates.parseMp4Date("1970-01-01T00:00:00Z"))
    }
}
