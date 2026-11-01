package com.hebi.quickcut.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal data class CutChoice<T>(val value: T, val label: String, val enabled: Boolean = true)

@Composable
internal fun <T> CutChoiceDropdown(
    label: String,
    selected: T,
    options: List<CutChoice<T>>,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.value == selected }?.label.orEmpty()
    // Keep the caller's semantics/test tag on the actual clickable anchor.
    Box {
        Button(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { contentDescription = "$label: $currentLabel" },
            shape = CutShape,
        ) {
            Text("$label: $currentLabel ▾")
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    enabled = option.enabled,
                    modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { this.selected = option.value == selected },
                    onClick = {
                        expanded = false
                        onSelected(option.value)
                    },
                )
            }
        }
    }
}
