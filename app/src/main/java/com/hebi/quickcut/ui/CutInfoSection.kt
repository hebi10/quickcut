package com.hebi.quickcut.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared reference-style information section. It never changes the underlying action. */
@Composable
internal fun CutInfoSection(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    caution: Boolean = false,
) {
    val palette = LocalCutPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (caution) MaterialTheme.colorScheme.errorContainer else palette.surface,
        contentColor = if (caution) MaterialTheme.colorScheme.onErrorContainer else palette.primaryText,
        shape = CutPanelShape,
        border = BorderStroke(1.dp, if (caution) palette.error else palette.border),
        
    ) {
        Column(
            Modifier.padding(CutSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(CutSpacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
