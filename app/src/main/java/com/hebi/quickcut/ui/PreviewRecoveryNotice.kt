package com.hebi.quickcut.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A preview failure leaves editing and saving available, with an explicit prepare retry. */
@Composable
internal fun PreviewRecoveryNotice(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 180.dp)
        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Text(message, color = CutColors.secondaryText)
        TextButton(onClick = onRetry, shape = CutShape) { Text("미리보기 다시 시도") }
        ExportDiagnosticButton()
    }
}
