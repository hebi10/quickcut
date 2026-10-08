package com.hebi.quickcut.editor

/**
 * Explicit per-export choice. KEEP is the safe default and is never inferred from a template.
 */
enum class OriginalAction {
    KEEP,
    TRASH,
    DELETE,
}

enum class OriginalActionStatus {
    NONE,
    READY,
    CONFIRMING,
    SYSTEM_PROMPT,
    COMPLETED,
    SKIPPED,
    UNSUPPORTED,
}

/** Sources actually referenced by the successfully exported timeline; each original occurs once. */
fun uniqueExportSources(timeline: EditTimeline): List<String> =
    timeline.clips.map { it.uri }.distinct()
