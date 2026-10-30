package com.hebi.quickcut.ui

import com.hebi.quickcut.editor.EditTimeline

/** ExoPlayer's quick preview applies mute through volume, without replacing its loaded media. */
internal fun quickTrimPreviewTimeline(timeline: EditTimeline): EditTimeline =
    EditTimeline(timeline.clips.map { it.copy(muted = false) })
