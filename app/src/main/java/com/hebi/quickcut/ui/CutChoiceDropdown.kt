package com.hebi.quickcut.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

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
    var anchorWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val currentLabel = options.firstOrNull { it.value == selected }?.label.orEmpty()
    // Keep the caller's semantics/test tag on the actual clickable anchor.
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
                .onSizeChanged { anchorWidth = it.width }
                .semantics { contentDescription = "$label: $currentLabel" },
            shape = CutShape,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall,
                        color = CutColors.secondaryText)
                    Text(currentLabel, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)
                }
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null,
                    modifier = Modifier.size(22.dp))
            }
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = if (anchorWidth > 0) Modifier.width(with(density) { anchorWidth.toDp() }) else Modifier,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    trailingIcon = if (option.value == selected) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, tint = CutColors.accent) }
                    } else null,
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
