package com.hebi.quickcut.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** License texts ship in the APK and remain readable without a network connection. */
@Composable
internal fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sourceReleaseUrl = remember(context) {
        @Suppress("DEPRECATION")
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        "https://github.com/hebi10/quickcut/releases" +
            (version?.takeIf(String::isNotBlank)?.let { "/tag/v$it" } ?: "")
    }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var contents by remember { mutableStateOf("") }
    LaunchedEffect(context) {
        names = withContext(Dispatchers.IO) {
            context.assets.list("licenses").orEmpty()
                .filter { it.endsWith(".txt") || it.endsWith(".md") }
                .sortedWith(compareBy<String> { it != "NOTICE.txt" }.thenBy { it })
        }
    }
    LaunchedEffect(selected) {
        contents = ""
        selected?.let { name ->
            contents = withContext(Dispatchers.IO) {
                context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(selected ?: "오픈소스 라이선스") },
        text = {
            if (selected == null) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text("QuickCut에 포함된 소프트웨어의 저작권과 사용 조건입니다. " +
                            "이 통합판은 GPLv3에 따라 제공됩니다.\n\n" +
                            "이 버전의 전체 대응 소스와 빌드 안내는 아래 릴리스의 " +
                            "source.tar.gz 및 source-guide.md에서 받을 수 있습니다.\n" +
                            "$sourceReleaseUrl\n\n" +
                            "개발 저장소:\nhttps://github.com/hebi10/quickcut")
                    }
                    names.forEach { name ->
                        TextButton(onClick = { selected = name }, shape = CutShape) {
                            Text(name)
                        }
                    }
                }
            } else {
                SelectionContainer {
                    val paragraphs = remember(contents) {
                        contents.splitToSequence("\n\n").flatMap { it.chunked(8_000).asSequence() }.toList()
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(paragraphs) { Text(it + "\n") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shape = CutShape) { Text("닫기") }
        },
        dismissButton = {
            if (selected != null) {
                TextButton(onClick = { selected = null }, shape = CutShape) { Text("목록") }
            }
        },
        containerColor = CutColors.panel,
        shape = CutShape,
    )
}
