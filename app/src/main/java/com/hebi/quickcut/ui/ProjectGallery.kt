package com.hebi.quickcut.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.quickcut.editor.SavedProject
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.media.MediaReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ProjectCard(
    item: SavedProject,
    onOpen: (String) -> Unit,
    onDelete: (String) -> String?,
) {
    val palette = LocalCutPalette.current
    var confirmDelete by remember(item.id) { mutableStateOf(false) }
    var deleteError by remember(item.id) { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 96.dp)
            .background(palette.surface, CutShape)
            .border(1.dp, palette.border, CutShape)
            .testTag("quickcut-project-card-" + item.id)
            .clickable { onOpen(item.id) }
            .padding(CutSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CutSpacing.md),
    ) {
        ClipThumbnail(
            item.thumbnailUri,
            item.thumbnailTimeMs,
            Modifier.size(68.dp).background(palette.panel, CutShape),
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(CutSpacing.xs),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.primaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${item.clipCount}개 클립",
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondaryText,
            )
            Text(
                "수정 " + DateFormat.getDateInstance(DateFormat.SHORT)
                    .format(Date(item.modifiedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondaryText,
            )
        }
        IconButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.size(CutSizes.touchTarget),
        ) {
            Icon(
                CutIcons.Delete,
                contentDescription = "프로젝트 삭제",
                tint = palette.secondaryAccent,
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = {
                confirmDelete = false
                deleteError = null
            },
            title = { QuickCutDialogHeading("프로젝트 삭제") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(CutSpacing.sm)) {
                    CutInfoSection(
                        title = "편집 기록 삭제",
                        message = "선택한 프로젝트의 저장된 편집 기록만 삭제합니다. " +
                            "원본 영상은 삭제하지 않습니다.",
                        caution = true,
                    )
                    Text(
                        "삭제한 편집 기록은 되돌릴 수 없습니다.",
                        color = palette.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    deleteError?.let { message ->
                        Text(message, color = palette.secondaryText,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                val failure = onDelete(item.id)
                if (failure == null) {
                    confirmDelete = false
                    deleteError = null
                } else {
                    deleteError = failure
                }
            }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = {
                confirmDelete = false
                deleteError = null
            }) { Text("취소") } },
            containerColor = palette.panel,
            shape = CutDialogShape,
        )
    }
}

internal data class GalleryVideoInfo(val displayName: String?, val durationMs: Long?)

internal fun readGalleryVideoInfo(context: Context, source: String): GalleryVideoInfo {
    val uri = Uri.parse(source)
    val name = runCatching {
        if (uri.scheme == "file") {
            uri.lastPathSegment
        } else {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }?.take(100)
    val durationMs = runCatching { MediaReader.metadata(context, uri).durationMs }.getOrNull()
    return GalleryVideoInfo(name, durationMs)
}

@Composable
internal fun SelectedVideoGallery(
    uris: List<String>,
    onAdd: () -> Unit,
    onImport: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onHelp: (String) -> Unit = {},
) {
    val palette = LocalCutPalette.current
    val context = LocalContext.current.applicationContext
    var selected by remember(uris) { mutableStateOf(uris.toSet()) }
    Column(
        Modifier.fillMaxSize().background(palette.background).padding(CutSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(CutSpacing.md),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(CutIcons.Back, contentDescription = "선택 닫기")
                }
                Text("영상 선택", style = MaterialTheme.typography.headlineSmall,
                    color = palette.primaryText)
            }
            Text("${selected.size}개 선택", color = palette.secondaryAccent,
                style = MaterialTheme.typography.labelMedium)
        }
        Text("추가한 영상에서 편집할 항목을 선택하세요.",
            color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
        FirstVisitGuideBanner("gallery", onHelp)
        TextButton(onClick = { onHelp("gallery") },
            modifier = Modifier.heightIn(min = 48.dp).testTag("gallery-help")) {
            Text("영상 선택 사용법")
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = if (maxWidth < 600.dp) GridCells.Fixed(2)
                else GridCells.Adaptive(160.dp)
            LazyVerticalGrid(
                columns = columns,
                modifier = Modifier.fillMaxSize().testTag("gallery-grid"),
                horizontalArrangement = Arrangement.spacedBy(CutSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(CutSpacing.sm),
            ) {
                itemsIndexed(uris, key = { _, uri -> uri }) { index, uri ->
                    val isSelected = uri in selected
                    val info by produceState<GalleryVideoInfo?>(initialValue = null, key1 = uri) {
                        value = withContext(Dispatchers.IO) { readGalleryVideoInfo(context, uri) }
                    }
                    val title = info?.displayName ?: "영상 ${index + 1}"
                    val duration = info?.durationMs?.let { "길이 ${formatTime(it)}" }
                        ?: if (info == null) "영상 정보 확인 중" else "길이 확인 불가"
                    val selectedOrder = uris.take(index + 1).count { it in selected }
                    Column(
                        Modifier.background(
                            if (isSelected) palette.accentMuted else palette.surface, CutShape
                        ).border(1.dp,
                            if (isSelected) palette.accent else palette.border, CutShape)
                            .testTag("gallery-item-$index")
                            .clickable {
                                selected = if (isSelected) selected - uri else selected + uri
                            }
                            .semantics {
                                stateDescription = if (isSelected) "선택됨, ${selectedOrder}번째 영상"
                                    else "선택 안 됨"
                            }
                            .padding(CutSpacing.xs),
                        verticalArrangement = Arrangement.spacedBy(CutSpacing.xs),
                    ) {
                        Box {
                            ClipThumbnail(uri, 0L, Modifier.fillMaxWidth().height(124.dp))
                            if (isSelected) {
                                Text("✓ $selectedOrder", color = palette.onAccent,
                                    modifier = Modifier.align(Alignment.TopEnd)
                                        .background(palette.accent, CutSmallShape)
                                        .padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                        Text(title, color = palette.primaryText,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("gallery-video-title-$index"))
                        Text(duration, color = palette.secondaryText,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("gallery-video-length-$index"))
                    }
                }
            }
        }
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
            modifier = Modifier.fillMaxWidth().heightIn(min = CutSizes.primaryButton)
                .testTag("gallery-import"),
            shape = CutShape) {
            Icon(CutIcons.Detailed, contentDescription = null)
            Text("선택한 영상 편집 (${selected.size})")
        }
    }
}
