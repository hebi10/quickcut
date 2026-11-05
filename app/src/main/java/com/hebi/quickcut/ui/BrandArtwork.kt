package com.hebi.quickcut.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

@Composable
internal fun QuickCutDialogHeading(title: String) {
    val icon = when {
        title.contains("삭제") -> Icons.Outlined.DeleteOutline
        title.contains("취소") || title.contains("종료") -> Icons.Outlined.WarningAmber
        title.contains("복원") -> Icons.Outlined.History
        title.contains("라이선스") || title.contains("개인정보") || title.contains("진단") ->
            Icons.Outlined.Info
        title.contains("저장") -> Icons.Outlined.Save
        else -> Icons.Outlined.Settings
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CutColors.accentMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = CutColors.secondaryAccent,
                modifier = Modifier.size(22.dp))
        }
        Text(title, color = CutColors.primaryText,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleLarge)
    }
}
