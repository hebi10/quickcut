package com.hebi.quickcut.media

import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Retry facts are deliberately independent of Android exceptions and raw exception text. */
internal data class ExportCodecRetryFacts(
    val media3ErrorCode: Int?,
    val codecErrorCode: Int? = null,
    val recoverable: Boolean = false,
    val transient: Boolean = false,
    val videoDecoder: Boolean = false,
    val failedSoftwareVideoDecoder: Boolean = false,
)

internal enum class ExportRetryMode { SAME_CONFIGURATION, SOFTWARE_VIDEO }

internal fun codecRetryMode(facts: ExportCodecRetryFacts): ExportRetryMode? {
    // Decoder initialization already tries alternative candidates inside DefaultDecoderFactory.
    // Only positive codec recovery/resource evidence warrants another initialization attempt.
    if (facts.media3ErrorCode !in listOf(3001, 3002, 4001, 4002)) return null
    // Native MediaErrors.h IO/malformed/unsupported, plus permanent errno/config failures.
    // Read these only for policy: diagnostic persistence keeps its existing allowlist.
    if (facts.codecErrorCode in listOf(-1004, -1007, -1010, -1, -13, -22, -28, -30, -38)) return null
    val resourceOrRecoverable = facts.recoverable || facts.transient ||
        facts.codecErrorCode == 1100 || facts.codecErrorCode == 1101
    val softwarePath = facts.videoDecoder && !facts.failedSoftwareVideoDecoder
    if (facts.media3ErrorCode == 3002 && softwarePath) return ExportRetryMode.SOFTWARE_VIDEO
    if (!resourceOrRecoverable) return null
    return if (softwarePath) ExportRetryMode.SOFTWARE_VIDEO else ExportRetryMode.SAME_CONFIGURATION
}

/** One shared budget for frame loss and codec failure; each attempt must finish releasing first. */
internal suspend fun <T> recoverMedia3Export(
    classifyCodec: (Exception) -> ExportCodecRetryFacts?,
    export: suspend (ExportRetryMode?) -> T,
): T {
    currentCoroutineContext().ensureActive()
    return try {
        export(null)
    } catch (first: Exception) {
        if (hasNonRetryableExportFailure(first)) throw first
        val retry = when (first) {
            is ExactExportFrameCountException ->
                if (first.hasMissingFrames) ExportRetryMode.SOFTWARE_VIDEO else null
            else -> classifyCodec(first)?.let(::codecRetryMode)
        } ?: throw first
        try {
            // A failure can be delivered after cancellation; never start another graph then.
            currentCoroutineContext().ensureActive()
            export(retry)
        } catch (next: Exception) {
            if (next !== first) next.addSuppressed(first)
            throw next
        }
    }
}

/** Fail closed for blocked nested/suppressed failures and unexpectedly large exception graphs. */
private fun hasNonRetryableExportFailure(error: Throwable): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = java.util.ArrayDeque<Throwable>()
    pending.add(error)
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (!seen.add(next)) continue
        if (seen.size > 64) return true
        if (next is CancellationException || next is SecurityException ||
            next is IllegalArgumentException || next is UnsupportedOperationException ||
            (next is IOException && next !is ExactExportFrameCountException)) return true
        if (next.suppressed.size + pending.size + seen.size > 64) return true
        next.cause?.let(pending::addLast)
        next.suppressed.forEach(pending::addLast)
    }
    return false
}

internal class ExportAttemptCleanupException(cause: Exception) :
    IOException("완료되지 않은 영상 처리 자원을 정리할 수 없습니다.", cause)

/** Cleanup is part of the attempt, so a release failure cannot become a codec retry. */
internal suspend fun <T> withReleasedExportAttempt(
    release: suspend () -> Unit,
    attempt: suspend () -> T,
): T {
    var failure: Throwable? = null
    try {
        return attempt()
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            withContext(NonCancellable) { release() }
        } catch (cleanup: Exception) {
            val wrapped = ExportAttemptCleanupException(cleanup)
            if (failure is CancellationException) {
                failure.addSuppressed(wrapped)
            } else {
                failure?.let(wrapped::addSuppressed)
                throw wrapped
            }
        }
    }
}

/** Private attempt files are finalized before the caller can validate or publish once. */
internal suspend fun <T> recoverMedia3ExportFile(
    file: File,
    newRetryFile: () -> File,
    classifyCodec: (Exception) -> ExportCodecRetryFacts?,
    export: suspend (File, ExportRetryMode?) -> T,
): T {
    var retryFile: File? = null
    try {
        val result = recoverMedia3Export(classifyCodec) { retry ->
            val attemptFile = if (retry == null) file else {
                withContext(Dispatchers.IO) {
                    if (file.exists() && !file.delete()) {
                        throw IOException("완료되지 않은 저장 시도의 임시 파일을 정리할 수 없습니다.")
                    }
                }
                newRetryFile().also {
                    require(it.absoluteFile != file.absoluteFile) { "Retry requires a fresh output path" }
                    retryFile = it
                }
            }
            currentCoroutineContext().ensureActive()
            export(attemptFile, retry)
        }
        // Finalization errors are never another codec attempt.
        retryFile?.let { completed ->
            withContext(Dispatchers.IO) {
                if (!completed.renameTo(file)) {
                    throw IOException("완료된 저장 시도의 임시 파일을 이동할 수 없습니다.")
                }
            }
        }
        return result
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { retryFile?.delete() }
    }
}
