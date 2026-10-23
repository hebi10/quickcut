package com.hebi.quickcut.media

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExactExportFrameRecoveryTest {
    @Test fun observedFrameLossRetriesOnceWithSoftwareRequested() = runBlocking {
        val modes = mutableListOf<Boolean>()
        val result = recoverExactExportFrames { software ->
            modes += software
            if (!software) throw ExactExportFrameCountException(60, 56)
            "complete"
        }
        assertEquals("complete", result)
        assertEquals(listOf(false, true), modes)
    }

    @Test fun failedRetryRetainsTheInitialFrameLossAndCannotRetryAgain() = runBlocking {
        val first = ExactExportFrameCountException(60, 56)
        val second = ExactExportFrameCountException(60, 59)
        val modes = mutableListOf<Boolean>()
        try {
            recoverExactExportFrames<Unit> { software -> modes += software; throw if (software) second else first }
            fail("A second failure must be returned")
        } catch (error: ExactExportFrameCountException) {
            assertSame(second, error)
            assertArrayEquals(arrayOf(first), error.suppressed)
        }
        assertEquals(listOf(false, true), modes)
    }

    @Test fun unavailableSoftwareDecoderRetainsInitialFrameLoss() = runBlocking {
        val first = ExactExportFrameCountException(60, 56)
        val unavailable = IOException("No software decoder available")
        try {
            recoverExactExportFrames<Unit> { software -> throw if (software) unavailable else first }
            fail("An unavailable retry must fail")
        } catch (error: IOException) {
            assertSame(unavailable, error)
            assertArrayEquals(arrayOf(first), error.suppressed)
        }
    }

    @Test fun normalSuccessDoesNotRequestSoftware() = runBlocking {
        val modes = mutableListOf<Boolean>()
        recoverExactExportFrames { modes += it; "complete" }
        assertEquals(listOf(false), modes)
    }

    @Test fun otherFailuresAndExtraPicturesDoNotTriggerSoftwareRetry() = runBlocking {
        for (failure in listOf(IOException("storage"), ExactExportFrameCountException(60, 61),
            ExactExportFrameCountException(null, 60), CancellationException("cancelled"))) {
            val modes = mutableListOf<Boolean>()
            try {
                recoverExactExportFrames<Unit> { modes += it; throw failure }
                fail("The original failure must be returned")
            } catch (error: Exception) { assertSame(failure, error) }
            assertEquals(listOf(false), modes)
        }
    }

    @Test fun retryCancellationRetainsCancellationAndTheInitialFrameLoss() = runBlocking {
        val first = ExactExportFrameCountException(60, 56)
        val cancelled = CancellationException("cancelled")
        try {
            recoverExactExportFrames<Unit> { software -> throw if (software) cancelled else first }
            fail("Cancellation must be returned")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
            assertArrayEquals(arrayOf(first), error.suppressed)
        }
    }
}
