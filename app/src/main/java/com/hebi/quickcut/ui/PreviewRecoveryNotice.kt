package com.hebi.quickcut.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Preview failure does not discard the user's edit or modify export/restore policy. */
@Composable
internal fun PreviewRecoveryNotice(message: String, onRetry: () -> Unit) {
    val palette = LocalCutPalette.current
    Column(
        Modifier.fillMaxWidth().heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CutSpacing.md, vertical = CutSpacing.sm)
            .background(palette.surface, CutShape)
            .border(1.dp, palette.border, CutShape)
            .padding(CutSpacing.lg)
            .testTag("ui06a-preview-recovery"),
        verticalArrangement = Arrangement.spacedBy(CutSpacing.sm),
    ) {
        Text("미리보기 확인 필요",
            color = palette.primaryText,
            style = MaterialTheme.typography.titleSmall)
        Text(message,
            color = palette.error,
            style = MaterialTheme.typography.bodyMedium)
        Text("편집 내용은 유지됩니다. 미리보기만 다시 준비할 수 있습니다.",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = onRetry,
            shape = CutShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = CutSizes.touchTarget),
        ) { Text("미리보기 다시 시도") }
        ExportDiagnosticButton()
    }
}
