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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hebi.quickcut.media.ExportDiagnosticHistory
import com.hebi.quickcut.media.ExportDiagnosticRecord
import com.hebi.quickcut.media.ExportDiagnosticStore

@Composable
internal fun ExportDiagnosticButton(operationId: String? = null, label: String = "최근 저장 진단") {
    val context = LocalContext.current
    var history by remember { mutableStateOf<ExportDiagnosticHistory?>(null) }
    var selected by remember { mutableStateOf<ExportDiagnosticRecord?>(null) }
    var missing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    TextButton(onClick = {
        val loaded = ExportDiagnosticStore.history(context)
        history = loaded
        selected = operationId?.let(loaded::record)
        missing = operationId != null && selected == null
        copied = false
    }, shape = CutShape) { Text(label) }
    history?.let { records ->
        AlertDialog(
            onDismissRequest = { history = null },
            title = { Text(if (selected == null) "저장 진단 기록" else "저장 작업 진단") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    val record = selected
                    if (record != null) {
                        TextButton(onClick = { selected = null; copied = false }) { Text("기록 목록으로") }
                        SelectionContainer { Text(record.text) }
                    } else {
                        Text("최근 ${ExportDiagnosticHistory.RECENT_LIMIT}건과 최근 실패 ${ExportDiagnosticHistory.FAILURE_LIMIT}건을 기기 안에 보관합니다. 자동 전송되지 않습니다.\n")
                        if (missing) Text("이 작업의 진단은 보관 개수를 넘었거나 기록을 읽을 수 없어 표시할 수 없습니다.\n")
                        Text("최근 실패", Modifier.semantics { heading() })
                        if (records.failures.isEmpty()) Text("기록된 실패가 없습니다.")
                        records.failures.forEach { item ->
                            DiagnosticRecordRow(item) { selected = item; copied = false }
                        }
                        Text("최근 저장 작업", Modifier.semantics { heading() })
                        if (records.recent.isEmpty()) Text("기록된 저장 진단이 없습니다.")
                        records.recent.forEach { item ->
                            DiagnosticRecordRow(item) { selected = item; copied = false }
                        }
                    }
                }
            },
            confirmButton = {
                selected?.let { record ->
                    TextButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("QuickCut 저장 진단", record.text))
                        copied = true
                    }) { Text(if (copied) "복사됨" else "진단 복사") }
                }
            },
            dismissButton = { TextButton(onClick = { history = null }) { Text("닫기") } },
            containerColor = CutColors.panel, shape = CutShape,
        )
    }
}

@Composable
private fun DiagnosticRecordRow(record: ExportDiagnosticRecord, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text("${record.status.label} · ${record.stage.label} · ${record.elapsedMs}ms\n작업 ${record.operationId.take(8)}")
    }
}
