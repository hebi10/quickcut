package com.hebi.quickcut.media

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import com.hebi.quickcut.editor.TextLayer
import com.hebi.quickcut.editor.TextLayerAlignment

/** The same clip-local effect is consumed by CompositionPlayer and Transformer. */
@OptIn(UnstableApi::class)
object TextOverlayEffects {
    fun videoEffects(layers: List<TextLayer>): List<Effect> {
        val active = layers.filter { it.text.isNotBlank() }
        if (active.isEmpty()) return emptyList()
        return listOf(OverlayEffect(active.map(::effect)))
    }

    fun visibleText(layer: TextLayer, presentationTimeUs: Long): String =
        if (presentationTimeUs >= 0 && layer.visibleAt(presentationTimeUs / 1_000L)) {
            layer.text
        } else ""

    private fun effect(layer: TextLayer): TextOverlay {
        val text = SpannableString(layer.text).apply {
            setSpan(ForegroundColorSpan(layer.color.argb), 0, length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(layer.size / 100f), 0, length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val align = when (layer.alignment) {
                TextLayerAlignment.START -> android.text.Layout.Alignment.ALIGN_NORMAL
                TextLayerAlignment.CENTER -> android.text.Layout.Alignment.ALIGN_CENTER
                TextLayerAlignment.END -> android.text.Layout.Alignment.ALIGN_OPPOSITE
            }
            setSpan(AlignmentSpan.Standard(align), 0, length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (layer.darkBackground) {
                setSpan(BackgroundColorSpan(0xCC101014.toInt()), 0, length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val empty = SpannableString("")
        val settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(2f * layer.x - 1f, 1f - 2f * layer.y)
            .setOverlayFrameAnchor(0f, 0f)
            .build()
        return object : TextOverlay() {
            override fun getText(presentationTimeUs: Long): SpannableString =
                if (visibleText(layer, presentationTimeUs).isNotEmpty()) text else empty

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
        }
    }
}
