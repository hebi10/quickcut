package com.hebi.quickcut.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hebi.quickcut.media.ExportDiagnosticHistory
import com.hebi.quickcut.media.ExportDiagnosticRecord
import com.hebi.quickcut.media.ExportDiagnosticStore

@Composable
internal fun ExportDiagnosticButton(operationId: String? = null, label: String = "최근 작업 진단") {
    val palette = LocalCutPalette.current
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
    }, shape = CutShape, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
    history?.let { records ->
        AlertDialog(
            onDismissRequest = { history = null },
            title = { QuickCutDialogHeading(if (selected == null) "편집·저장 진단 기록" else "저장 작업 진단") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val record = selected
                    if (record != null) {
                        TextButton(onClick = { selected = null; copied = false },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("기록 목록으로") }
                        Text("진단 상세", style = MaterialTheme.typography.titleSmall,
                            color = palette.primaryText)
                        SelectionContainer { Text(record.text) }
                        Text("복사한 진단에는 작업 정보가 포함될 수 있습니다. 공유 전에 내용을 확인하세요.",
                            color = palette.secondaryText,
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("최근 ${ExportDiagnosticHistory.RECENT_LIMIT}건과 실패 ${ExportDiagnosticHistory.FAILURE_LIMIT}건을 기기에 보관합니다. 자동 전송하지 않습니다.",
                            style = MaterialTheme.typography.bodySmall, color = palette.secondaryText)
                        if (missing) CutInfoSection(
                            title = "진단 기록 없음",
                            message = "이 작업의 진단은 보관 개수를 넘었거나 기록을 읽을 수 없어 표시할 수 없습니다.",
                        )
                        Text("최근 실패", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                        if (records.failures.isEmpty()) Text("기록된 실패가 없습니다.")
                        records.failures.forEach { item ->
                            DiagnosticRecordRow(item) { selected = item; copied = false }
                        }
                        Text("최근 작업", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                        if (records.recent.isEmpty()) Text("기록된 작업 진단이 없습니다.")
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
                            .setPrimaryClip(ClipData.newPlainText("QuickCut 편집·저장 진단", record.text))
                        copied = true
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (copied) "복사됨" else "진단 복사")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { history = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
            containerColor = palette.panel, shape = CutDialogShape,
        )
    }
}

@Composable
private fun DiagnosticRecordRow(record: ExportDiagnosticRecord, onClick: () -> Unit) {
    val palette = LocalCutPalette.current
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = CutSizes.touchTarget)
            .testTag("ui06a-diagnostic-record"),
        shape = CutShape,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(CutSpacing.xs),
        ) {
            Text(record.status.label + " · " + record.stage.label,
                color = if (record.status.label.contains("실패")) palette.error
                    else palette.primaryText,
                style = MaterialTheme.typography.bodyMedium)
            Text("작업 ${record.operationId.take(8)} · ${record.elapsedMs}ms",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
