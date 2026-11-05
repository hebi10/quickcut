package com.hebi.quickcut.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val brandGradient = Brush.linearGradient(
    listOf(CutColors.brandLight, CutColors.accent, CutColors.brandDeep),
)

/** Scissors and a play symbol reflect the QuickCut brand without adding a runtime asset. */
@Composable
internal fun QuickCutLogo(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(brandGradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ContentCut,
            contentDescription = null,
            modifier = Modifier.align(Alignment.CenterStart).size(size * 0.67f),
            tint = Color.White,
        )
        Icon(
            imageVector = Icons.Outlined.PlayArrow,
            contentDescription = null,
            modifier = Modifier.align(Alignment.CenterEnd).size(size * 0.43f),
            tint = Color.White,
        )
    }
}
