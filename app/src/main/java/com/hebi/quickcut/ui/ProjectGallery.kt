package com.hebi.quickcut.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.quickcut.editor.SavedProject
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ProjectCard(
    item: SavedProject,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var confirmDelete by remember(item.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(CutColors.surface, CutShape)
            .clickable { onOpen(item.id) }.padding(CutSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CutSpacing.md),
    ) {
        ClipThumbnail(item.thumbnailUri, item.thumbnailTimeMs,
            Modifier.size(84.dp).background(CutColors.panel, CutShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CutSpacing.xs)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium,
                color = CutColors.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${item.clipCount}개 클립 · " +
                DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.modifiedAt)),
                style = MaterialTheme.typography.bodySmall, color = CutColors.secondaryText)
        }
        IconButton(onClick = { confirmDelete = true }) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "프로젝트 삭제",
                tint = CutColors.secondaryAccent)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("프로젝트 삭제") },
            text = { Text("편집 기록만 삭제합니다. 원본 영상은 삭제하지 않습니다.") },
            confirmButton = { TextButton(onClick = {
                onDelete(item.id)
                confirmDelete = false
            }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
            containerColor = CutColors.panel,
            shape = CutDialogShape,
        )
    }
}

@Composable
internal fun SelectedVideoGallery(
    uris: List<String>,
    onAdd: () -> Unit,
    onImport: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(uris) { mutableStateOf(uris.toSet()) }
    Column(Modifier.fillMaxSize().background(CutColors.background)
        .padding(CutSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(CutSpacing.md)) {
        Text("영상 선택", style = MaterialTheme.typography.headlineSmall,
            color = CutColors.primaryText)
        Text("선택한 영상을 확인하고 편집에 사용할 항목을 고르세요.",
            color = CutColors.secondaryText, style = MaterialTheme.typography.bodyMedium)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(CutSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(CutSpacing.sm),
        ) {
            items(uris, key = { it }) { uri ->
                val isSelected = uri in selected
                Column(
                    Modifier.background(if (isSelected) CutColors.accentMuted else CutColors.surface,
                        CutShape).clickable {
                        selected = if (isSelected) selected - uri else selected + uri
                    }.padding(CutSpacing.xs),
                ) {
                    Box {
                        ClipThumbnail(uri, 0L, Modifier.fillMaxWidth().height(100.dp))
                        if (isSelected) {
                            Text("선택", color = CutColors.primaryText,
                                modifier = Modifier.align(Alignment.TopEnd)
                                    .background(CutColors.accent, CutSmallShape)
                                    .padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    Text("영상", color = CutColors.secondaryText,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text("${selected.size}개 선택", color = CutColors.secondaryAccent)
        Row(horizontalArrangement = Arrangement.spacedBy(CutSpacing.sm)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)
                .heightIn(min = CutSizes.primaryButton), shape = CutShape) {
                Text("취소")
            }
            OutlinedButton(onClick = onAdd, modifier = Modifier.weight(1f)
                .heightIn(min = CutSizes.primaryButton), shape = CutShape) {
                Text("영상 추가")
            }
        }
        Button(onClick = { onImport(uris.filter { it in selected }) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = CutSizes.primaryButton),
            shape = CutShape) {
            Icon(Icons.Outlined.Movie, contentDescription = null)
            Text("선택한 영상 편집")
        }
    }
}
