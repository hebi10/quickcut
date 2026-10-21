package com.hebi.quickcut.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hebi.quickcut.media.ExportDiagnosticStore

@Composable
internal fun ExportDiagnosticButton() {
    val context = LocalContext.current
    var details by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    TextButton(onClick = {
        details = ExportDiagnosticStore.latest(context) ?: "기록된 저장 진단이 없습니다."
        copied = false
    }, shape = CutShape) { Text("최근 저장 진단") }
    details?.let { text ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text("최근 저장 진단") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("마지막으로 시도한 한 건의 기록입니다. 일괄 저장에서는 마지막 처리 파일의 기록이 표시됩니다.\n")
                    SelectionContainer { Text(text) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("QuickCut 저장 진단", text))
                    copied = true
                }) { Text(if (copied) "복사됨" else "진단 복사") }
            },
            dismissButton = { TextButton(onClick = { details = null }) { Text("닫기") } },
            containerColor = CutColors.panel, shape = CutShape,
        )
    }
}
