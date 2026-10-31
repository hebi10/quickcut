package com.hebi.quickcut.media

import java.io.IOException
import java.nio.file.Files
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class Media3ExportRecoveryTest {
    private val hardwareDecode = ExportCodecRetryFacts(3002, videoDecoder = true)
    private val codecFailure = IllegalStateException("codec")

    @Test fun transientRecoverableAndResourceCodecFailuresAreClassified() {
        for (code in listOf(3001, 3002, 4001, 4002)) {
            for (facts in listOf(ExportCodecRetryFacts(code, transient = true),
                ExportCodecRetryFacts(code, recoverable = true),
                ExportCodecRetryFacts(code, codecErrorCode = 1100),
                ExportCodecRetryFacts(code, codecErrorCode = 1101))) {
                assertEquals(facts.toString(), ExportRetryMode.SAME_CONFIGURATION, codecRetryMode(facts))
            }
        }
    }

    @Test fun videoDecoderProcessingGetsSoftwareButInitDoesNotDuplicateBuiltInFallback() {
        assertEquals(ExportRetryMode.SOFTWARE_VIDEO, codecRetryMode(hardwareDecode))
        assertNull(codecRetryMode(ExportCodecRetryFacts(3001, videoDecoder = true)))
        assertNull(codecRetryMode(ExportCodecRetryFacts(3002))) // No known video decoder path.
        assertNull(codecRetryMode(hardwareDecode.copy(failedSoftwareVideoDecoder = true)))
        assertEquals(ExportRetryMode.SAME_CONFIGURATION,
            codecRetryMode(hardwareDecode.copy(failedSoftwareVideoDecoder = true, transient = true)))
    }

    @Test fun storagePermissionMalformedUnsupportedMuxValidationAndUnknownNeverRetryEvenWithCodecFlags() {
        for (code in listOf(null, 1000, 1001, 2000, 2005, 2006, 2008, 3003, 4003, 5001, 6001, 7001, 7002, 7003)) {
            assertNull("code=$code", codecRetryMode(ExportCodecRetryFacts(code,
                videoDecoder = true, recoverable = true, transient = true, codecErrorCode = 1100)))
        }
        assertNull(codecRetryMode(ExportCodecRetryFacts(4002)))
        assertNull(codecRetryMode(ExportCodecRetryFacts(4001, codecErrorCode = 1234)))
    }

    @Test fun permanentNativeCodecStatusesOverrideEvenRecoverableFlagsAndSoftwareFallback() {
        for (status in listOf(-1004, -1007, -1010, -1, -13, -22, -28, -30, -38)) {
            assertNull("native codec status=$status", codecRetryMode(hardwareDecode.copy(
                codecErrorCode = status, transient = true, recoverable = true)))
        }
    }

    @Test fun frameThenCodecFailureUsesOnlyOneAdditionalAttemptAndRetainsFirst() = runBlocking {
        val first = ExactExportFrameCountException(60, 56)
        val modes = mutableListOf<ExportRetryMode?>()
        try {
            recoverMedia3Export(classifyCodec = { hardwareDecode }) { mode ->
                modes += mode
                throw if (mode == null) first else codecFailure
            }
            fail("Second failure must escape")
        } catch (error: IllegalStateException) {
            assertSame(codecFailure, error)
            assertArrayEquals(arrayOf(first), error.suppressed)
        }
        assertEquals(listOf(null, ExportRetryMode.SOFTWARE_VIDEO), modes)
    }

    @Test fun codecThenFrameLossUsesTheSameBudget() = runBlocking {
        val modes = mutableListOf<ExportRetryMode?>()
        val second = ExactExportFrameCountException(60, 59)
        try {
            recoverMedia3Export(classifyCodec = { ExportCodecRetryFacts(4002, transient = true) }) { mode ->
                modes += mode
                throw if (mode == null) codecFailure else second
            }
            fail("Second failure must escape")
        } catch (error: ExactExportFrameCountException) {
            assertSame(second, error)
            assertArrayEquals(arrayOf(codecFailure), error.suppressed)
        }
        assertEquals(listOf(null, ExportRetryMode.SAME_CONFIGURATION), modes)
    }

    @Test fun completedAttemptsReleaseBeforeFreshAttemptAndPublicationRunsOnce() = runBlocking {
        val events = mutableListOf<String>()
        var publications = 0
        val result = recoverMedia3Export(classifyCodec = { hardwareDecode }) { mode ->
            withReleasedExportAttempt(release = { events += "release-$mode" }) {
                events += "attempt-$mode"
                if (mode == null) throw codecFailure
                "complete"
            }
        }
        publications++
        assertEquals("complete", result)
        assertEquals(1, publications)
        assertEquals(listOf("attempt-null", "release-null", "attempt-SOFTWARE_VIDEO", "release-SOFTWARE_VIDEO"), events)
    }

    @Test fun releaseFailureBlocksRetryAndPreservesTheProcessingFailure() = runBlocking {
        var attempts = 0
        val cleanup = IOException("cleanup")
        try {
            recoverMedia3Export(classifyCodec = { hardwareDecode }) {
                attempts++
                withReleasedExportAttempt(release = { throw cleanup }) { throw codecFailure }
            }
            fail("Failed cleanup must escape")
        } catch (error: ExportAttemptCleanupException) {
            assertSame(cleanup, error.cause)
            assertArrayEquals(arrayOf(codecFailure), error.suppressed)
        }
        assertEquals(1, attempts)
    }

    @Test fun cancelledContextCannotStartARecoveryEvenWhenFailureIsOtherwiseRetryable() = runBlocking {
        var attempts = 0
        var released = false
        val job = Job()
        try {
            withContext(job) {
                recoverMedia3Export(classifyCodec = { hardwareDecode }) {
                    attempts++
                    withReleasedExportAttempt(release = { released = true }) {
                        currentCoroutineContext().cancel()
                        throw codecFailure
                    }
                }
            }
            fail("Cancellation must escape")
        } catch (_: CancellationException) { }
        assertEquals(1, attempts)
        assertTrue(released)
    }

    @Test fun blockedCauseOrSuppressedFailureCannotBeRetried() = runBlocking {
        for (blocked in listOf(CancellationException("cancel"), IOException("storage/validation"),
            SecurityException("permission"), IllegalArgumentException("malformed"),
            UnsupportedOperationException("unsupported"), ExportAttemptCleanupException(IOException("cleanup")))) {
            for (suppressed in listOf(false, true)) {
                val wrapper = IllegalStateException("codec")
                if (suppressed) wrapper.addSuppressed(blocked) else wrapper.initCause(blocked)
                var attempts = 0
                try {
                    recoverMedia3Export(classifyCodec = { hardwareDecode }) { attempts++; throw wrapper }
                    fail("Original failure must escape")
                } catch (error: IllegalStateException) { assertSame(wrapper, error) }
                assertEquals("$blocked suppressed=$suppressed", 1, attempts)
            }
        }
    }

    @Test fun cancellationDuringRetryRetainsFirstAndCannotPublish() = runBlocking {
        val first = IllegalStateException("codec")
        val cancelled = CancellationException("cancelled")
        var publications = 0
        try {
            recoverMedia3Export(classifyCodec = { hardwareDecode }) { mode ->
                throw if (mode == null) first else cancelled
            }
            publications++
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
            assertArrayEquals(arrayOf(first), error.suppressed)
        }
        assertEquals(0, publications)
    }

    @Test fun oversizedAndCyclicExceptionGraphsAreBounded() = runBlocking {
        val oversized = IllegalStateException("outer")
        repeat(65) { oversized.addSuppressed(IllegalStateException("detail")) }
        var attempts = 0
        try {
            recoverMedia3Export(classifyCodec = { hardwareDecode }) { attempts++; throw oversized }
            fail("Oversized evidence must fail closed")
        } catch (error: IllegalStateException) { assertSame(oversized, error) }
        assertEquals(1, attempts)
        val cyclic = IllegalStateException("cycle")
        val blocked = IOException("blocked", cyclic)
        cyclic.initCause(blocked)
        try {
            recoverMedia3Export(classifyCodec = { hardwareDecode }) { attempts++; throw cyclic }
            fail("Cyclic evidence must still detect blocked errors")
        } catch (error: IllegalStateException) { assertSame(cyclic, error) }
        assertEquals(2, attempts)
    }

    @Test fun cleanupFailureCannotReplaceCancellation() = runBlocking {
        val cancelled = CancellationException("cancelled")
        try {
            withReleasedExportAttempt(release = { throw IOException("cleanup") }) { throw cancelled }
            fail("Cancellation must escape")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
            assertTrue(error.suppressed.single() is ExportAttemptCleanupException)
        }
    }

    @Test fun cleanupFailureAfterSuccessCannotTriggerRecoveryOrPublication() = runBlocking {
        var attempts = 0
        var publications = 0
        try {
            recoverMedia3Export(classifyCodec = { hardwareDecode }) {
                attempts++
                withReleasedExportAttempt(release = { throw IOException("cleanup") }) { "complete" }
            }
            publications++
        } catch (_: ExportAttemptCleanupException) { }
        assertEquals(1, attempts)
        assertEquals(0, publications)
    }

    @Test fun failedFileIsRemovedBeforeFreshOutputAndOnlyCompleteRetryIsFinalized() = runBlocking {
        val directory = Files.createTempDirectory("export-retry").toFile()
        val finalFile = File(directory, "final.mp4")
        val retryFile = File(directory, "retry.mp4")
        val paths = mutableListOf<File>()
        try {
            val result = recoverMedia3ExportFile(finalFile, { retryFile }, { hardwareDecode }) { file, mode ->
                paths += file
                if (mode == null) {
                    file.writeText("partial")
                    throw codecFailure
                }
                assertFalse(finalFile.exists())
                assertFalse(retryFile.exists())
                file.writeText("complete")
                "done"
            }
            assertEquals("done", result)
            assertEquals(listOf(finalFile, retryFile), paths)
            assertEquals("complete", finalFile.readText())
            assertFalse(retryFile.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun outputCleanupFailurePreventsASecondProcessingAttempt() = runBlocking {
        val directory = Files.createTempDirectory("export-cleanup").toFile()
        val finalFile = File(directory, "nonempty-directory")
        finalFile.mkdir()
        File(finalFile, "occupied").writeText("owned pending data")
        var attempts = 0
        var retryFiles = 0
        try {
            try {
                recoverMedia3ExportFile(finalFile, { retryFiles++; File(directory, "retry.mp4") },
                    { hardwareDecode }) { _, _ -> attempts++; throw codecFailure }
                fail("Output cleanup must fail")
            } catch (error: IOException) {
                assertArrayEquals(arrayOf(codecFailure), error.suppressed)
            }
            assertEquals(1, attempts)
            assertEquals(0, retryFiles)
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedRetryFileIsRemovedAndNeverFinalized() = runBlocking {
        val directory = Files.createTempDirectory("export-failed-retry").toFile()
        val finalFile = File(directory, "final.mp4")
        val retryFile = File(directory, "retry.mp4")
        val second = IllegalStateException("second")
        try {
            try {
                recoverMedia3ExportFile(finalFile, { retryFile }, { hardwareDecode }) { file, mode ->
                    file.writeText("partial")
                    throw if (mode == null) codecFailure else second
                }
                fail("Failed retry must escape")
            } catch (error: IllegalStateException) { assertSame(second, error) }
            assertFalse(finalFile.exists())
            assertFalse(retryFile.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun finalizationFailureCannotStartAThirdAttempt() = runBlocking {
        val directory = Files.createTempDirectory("export-finalize").toFile()
        val finalFile = File(directory, "final.mp4")
        var attempts = 0
        try {
            try {
                recoverMedia3ExportFile(finalFile, { File(directory, "missing.mp4") }, { hardwareDecode }) { _, mode ->
                    attempts++
                    if (mode == null) throw codecFailure
                    "done" // Deliberately no completed output for finalization.
                }
                fail("Missing output must escape finalization")
            } catch (_: IOException) { }
            assertEquals(2, attempts)
        } finally { directory.deleteRecursively() }
    }

    @Test fun ordinarySuccessAndUnclassifiedErrorDoNotRetry() = runBlocking {
        var attempts = 0
        assertEquals("done", recoverMedia3Export(classifyCodec = { null }) { attempts++; "done" })
        assertEquals(1, attempts)
        try {
            recoverMedia3Export(classifyCodec = { null }) { attempts++; throw codecFailure }
            fail("Unclassified error must escape")
        } catch (error: IllegalStateException) { assertSame(codecFailure, error) }
        assertEquals(2, attempts)
    }
}
