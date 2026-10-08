package com.hebi.quickcut.media

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip

@OptIn(UnstableApi::class)
object CompositionFactory {
    fun mediaItem(clip: VideoClip): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.parse(clip.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.startMs)
                    .setEndPositionMs(clip.endMs)
                    .build()
            )
            .build()

    fun editedClip(clip: VideoClip): EditedMediaItem =
        EditedMediaItem.Builder(mediaItem(clip))
            .setDurationUs(clip.sourceDurationMs * 1_000L)
            .setRemoveAudio(clip.muted || !clip.hasAudio)
            .build()

    fun from(timeline: EditTimeline): Composition {
        require(!timeline.isEmpty) { "편집할 영상이 없습니다." }
        val items = timeline.clips.map(::editedClip)
        val withAudio = timeline.clips.any { !it.muted && it.hasAudio }
        val sequence = if (withAudio) {
            EditedMediaItemSequence.withAudioAndVideoFrom(items)
        } else {
            EditedMediaItemSequence.withVideoFrom(items)
        }
        return Composition.Builder(sequence).build()
    }
}
