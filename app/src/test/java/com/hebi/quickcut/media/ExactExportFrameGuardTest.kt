package com.hebi.quickcut.media

import org.junit.Assert.*
import org.junit.Test

class ExactExportFrameGuardTest {
    @Test fun aCompletedExportMustRetainEveryRegisteredPicture() {
        val guard = ExactExportFrameGuard()
        val pipeline = guard.beginPipeline()
        repeat(60) { pipeline.registered(true) }
        val error = assertThrows(ExactExportFrameCountException::class.java) {
            guard.verify(56, fullyTranscoded = true)
        }
        assertEquals(60L, error.expectedFrames)
        assertEquals(56, error.outputFrames)
    }

    @Test fun everyRetainedPicturePassesWithoutFrameRateOrTimestampGuesses() {
        val guard = ExactExportFrameGuard()
        val pipeline = guard.beginPipeline()
        repeat(47) { pipeline.registered(true) }
        guard.verify(47, fullyTranscoded = true)
    }

    @Test fun backpressureDoesNotCountFramesTheProcessorRejected() {
        val guard = ExactExportFrameGuard()
        val pipeline = guard.beginPipeline()
        repeat(60) { pipeline.registered(false); pipeline.registered(true) }
        guard.verify(60, fullyTranscoded = true)
    }

    @Test fun aDiscardedOptimizationPipelineDoesNotInflateTheFinalFullExport() {
        val guard = ExactExportFrameGuard()
        val abandoned = guard.beginPipeline()
        repeat(4) { abandoned.registered(true) }
        val completed = guard.beginPipeline()
        repeat(60) { completed.registered(true) }
        // Late registration from a released/abandoned pipeline cannot alter the new ledger.
        abandoned.registered(true)
        guard.verify(60, fullyTranscoded = true)
    }

    @Test fun anotherExportAttemptHasIndependentAccounting() {
        val first = ExactExportFrameGuard()
        val initialPipeline = first.beginPipeline()
        repeat(60) { initialPipeline.registered(true) }
        val retry = ExactExportFrameGuard()
        val pipeline = retry.beginPipeline()
        repeat(60) { pipeline.registered(true) }
        retry.verify(60, fullyTranscoded = true)
    }

    @Test fun copiedOrMixedOptimizedOutputsDoNotCompareCopiedPicturesToDecodedInputs() {
        val guard = ExactExportFrameGuard()
        val boundaryPipeline = guard.beginPipeline()
        repeat(4) { boundaryPipeline.registered(true) }
        guard.verify(60, fullyTranscoded = false)
        ExactExportFrameGuard().verify(60, fullyTranscoded = false)
    }

    @Test fun aFullTranscodeCannotSilentlySkipMissingRegistrationEvidence() {
        assertThrows(ExactExportFrameCountException::class.java) {
            ExactExportFrameGuard().verify(60, fullyTranscoded = true)
        }
    }

    @Test fun unexpectedExtraPicturesAreAlsoRejected() {
        val guard = ExactExportFrameGuard()
        val pipeline = guard.beginPipeline()
        repeat(60) { pipeline.registered(true) }
        assertThrows(ExactExportFrameCountException::class.java) {
            guard.verify(61, fullyTranscoded = true)
        }
    }
}
