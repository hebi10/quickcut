package com.hebi.quickcut.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.hebi.quickcut.R
import androidx.compose.ui.unit.dp

/** A preview failure leaves editing and saving available, with an explicit prepare retry. */
@Composable
internal fun PreviewRecoveryNotice(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(CutColors.panel, CutShape)
            .border(1.dp, CutColors.border, CutShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.quickcut_preview_error),
                contentDescription = null, modifier = Modifier.size(64.dp),
                colorFilter = ColorFilter.tint(CutColors.secondaryAccent))
            Column(Modifier.weight(1f)) {
                Text("미리보기 확인 필요", color = CutColors.secondaryAccent)
                Text(message, color = CutColors.primaryText)
            }
        }
        Text("편집 내용은 유지됩니다. 미리보기만 다시 준비할 수 있습니다.",
            color = CutColors.secondaryText)
        TextButton(onClick = onRetry, shape = CutShape,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("미리보기 다시 시도") }
        ExportDiagnosticButton()
    }
}
