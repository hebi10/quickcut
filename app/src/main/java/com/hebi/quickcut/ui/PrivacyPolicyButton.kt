package com.hebi.quickcut.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hebi.quickcut.R

@Composable
internal fun PrivacyPolicyButton() {
    var showing by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showing = true }, shape = CutShape,
        modifier = Modifier.heightIn(min = 48.dp)) {
        Text("개인정보처리방침", color = CutColors.mutedText,
            style = MaterialTheme.typography.labelMedium)
    }
    if (showing) PrivacyPolicyDialog(onDismiss = { showing = false })
}

@Composable
private fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val paragraphs = remember(context) {
        context.resources.openRawResource(R.raw.privacy_policy).bufferedReader().use {
            it.readText().split("\n\n")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("개인정보처리방침") },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    paragraphs.forEach { Text(it) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shape = CutShape,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") }
        },
        containerColor = CutColors.panel,
        shape = CutDialogShape,
    )
}
