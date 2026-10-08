package com.hebi.quickcut.media

enum class SaveDateMode {
    /** Save as a new file with the current creation/modified time. */
    CURRENT,

    /** Copy the original capture time (or the available file-modified time). */
    ORIGINAL,
}

data class OriginalTimestamp(
    val epochMs: Long,
    val source: String,
)
