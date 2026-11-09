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

/** The same flat film-frame cut mark is used by the launcher, splash and home. */
@Composable
internal fun QuickCutLogo(size: Dp, modifier: Modifier = Modifier) {
    val palette = LocalCutPalette.current
    Box(
        modifier = modifier.size(size)
            .clip(CutShape)
            .background(palette.accent),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.hebi.quickcut.R.drawable.ic_launcher_foreground),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(palette.onAccent),
            modifier = Modifier.size(size),
        )
    }
}

@Composable
internal fun QuickCutDialogHeading(title: String) {
    val palette = LocalCutPalette.current
    val caution = title.contains("삭제") || title.contains("취소") || title.contains("종료")
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
                .background(if (caution) MaterialTheme.colorScheme.errorContainer else palette.accentMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null,
                tint = if (caution) MaterialTheme.colorScheme.onErrorContainer else palette.secondaryAccent,
                modifier = Modifier.size(CutSizes.icon))
        }
        Text(title, modifier = Modifier.weight(1f), color = palette.primaryText,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleLarge)
    }
}
