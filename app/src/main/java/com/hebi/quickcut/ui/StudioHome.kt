package com.hebi.quickcut.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hebi.quickcut.editor.SavedProject
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickCutThemeMode
import com.hebi.quickcut.settings.QuickTrimSettings

@Composable
internal fun StudioBrand(modifier: Modifier = Modifier) {
    val palette = LocalCutPalette.current
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CutSpacing.md),
    ) {
        QuickCutLogo(size = CutSizes.touchTarget)
        Column(verticalArrangement = Arrangement.spacedBy(CutSpacing.xs)) {
            Text(
                "QuickCut",
                style = MaterialTheme.typography.titleLarge,
                color = palette.primaryText,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "영상 편집 작업대",
                color = palette.secondaryText,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
internal fun StudioStartScreen(
    onQuick: () -> Unit,
    onSelect: () -> Unit,
    onBatch: () -> Unit,
    onExtraction: () -> Unit = {},
    canRestore: Boolean,
    onRestore: () -> Unit,
    quickSettings: QuickTrimSettings,
    dateMode: SaveDateMode,
    onPreferences: () -> Unit,
    onLicenses: () -> Unit,
    projects: List<SavedProject> = emptyList(),
    onOpenProject: (String) -> Unit = {},
    onDeleteProject: (String) -> Unit = {},
    onGallery: () -> Unit = {},
    themeMode: QuickCutThemeMode = QuickCutThemeMode.SYSTEM,
    onThemeModeChange: (QuickCutThemeMode) -> Unit = {},
) {
    val palette = LocalCutPalette.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CutSpacing.lg, vertical = CutSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(CutSpacing.xl),
        ) {
            when (selectedTab) {
                0 -> {
                    StudioBrand()
                    Button(
                        onClick = onQuick,
                        shape = CutShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .testTag("quickcut-home-start"),
                    ) {
                        Icon(
                            CutIcons.Trim,
                            contentDescription = null,
                            modifier = Modifier.size(CutSizes.icon),
                        )
                        Spacer(Modifier.width(CutSpacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "+ 영상 편집 시작하기",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.onAccent,
                            )
                            Text(
                                "영상을 선택해 필요한 구간을 편집하세요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.onAccent,
                            )
                        }
                    }
                    if (LocalDensity.current.fontScale >= 1.6f) {
                        Column {
                            Text(
                                "최근 프로젝트",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.primaryText,
                            )
                            TextButton(
                                onClick = { selectedTab = 1 },
                                modifier = Modifier.heightIn(min = CutSizes.touchTarget),
                            ) {
                                Text("전체 보기 >", color = palette.secondaryAccent)
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "최근 프로젝트",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.primaryText,
                            )
                            TextButton(
                                onClick = { selectedTab = 1 },
                                modifier = Modifier.heightIn(min = CutSizes.touchTarget),
                            ) {
                                Text("전체 보기 >", color = palette.secondaryAccent)
                            }
                        }
                    }
                    if (projects.isEmpty()) {
                        Text(
                            "저장한 프로젝트가 없습니다. 편집한 영상을 프로젝트로 저장하면 여기에서 다시 열 수 있습니다.",
                            color = palette.secondaryText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("quickcut-home-empty-projects"),
                        )
                    } else {
                        projects.take(3).forEach { project ->
                            ProjectCard(project, onOpenProject, onDeleteProject)
                        }
                    }
                    if (canRestore) {
                        OutlinedButton(
                            onClick = onRestore,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = CutSizes.primaryButton),
                            shape = CutShape,
                        ) {
                            Icon(CutIcons.Restore, contentDescription = null)
                            Spacer(Modifier.width(CutSpacing.sm))
                            Text("이전 편집 이어서 진행")
                        }
                    }
                    OutlinedButton(
                        onClick = { selectedTab = 2 },
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = CutSizes.primaryButton),
                        shape = CutShape,
                    ) {
                        Text("편집 도구 보기")
                    }
                }
                1 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(CutSpacing.xs)) {
                        Text(
                            "내 프로젝트",
                            style = MaterialTheme.typography.headlineSmall,
                            color = palette.primaryText,
                        )
                        Text(
                            "저장한 프로젝트 ${projects.size}개",
                            color = palette.secondaryText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (projects.isEmpty()) {
                        CutInfoSection(
                            title = "프로젝트 없음",
                            message = "영상 편집 후 프로젝트를 저장하면 여기에서 이어서 작업할 수 있습니다.",
                        )
                        Button(
                            onClick = onQuick,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = CutSizes.primaryButton),
                            shape = CutShape,
                        ) { Text("영상 편집 시작하기") }
                    } else {
                        projects.forEach { project ->
                            ProjectCard(project, onOpenProject, onDeleteProject)
                        }
                    }
                    if (canRestore) {
                        OutlinedButton(
                            onClick = onRestore,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = CutSizes.primaryButton),
                            shape = CutShape,
                        ) {
                            Icon(CutIcons.Restore, contentDescription = null)
                            Spacer(Modifier.width(CutSpacing.sm))
                            Text("이전 편집 이어서 진행")
                        }
                    }
                }
                2 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(CutSpacing.xs)) {
                        Text(
                            "편집 도구",
                            style = MaterialTheme.typography.headlineSmall,
                            color = palette.primaryText,
                        )
                        Text(
                            "작업 방식에 맞는 도구를 선택하세요.",
                            color = palette.secondaryText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    StudioModes(onQuick, onBatch, onSelect, onExtraction)
                    OutlinedButton(
                        onClick = onGallery,
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = CutSizes.primaryButton),
                        shape = CutShape,
                    ) {
                        Icon(CutIcons.Projects, contentDescription = null)
                        Spacer(Modifier.width(CutSpacing.sm))
                        Text("영상 갤러리")
                    }
                }
                3 -> {
                    Text(
                        "설정",
                        style = MaterialTheme.typography.headlineSmall,
                        color = palette.primaryText,
                    )
                    StudioSettings(
                        quickSettings,
                        dateMode,
                        themeMode,
                        onThemeModeChange,
                        onPreferences,
                    )
                    StudioSettingsPanel("진단 및 정보") {
                        ExportDiagnosticButton()
                        PrivacyPolicyButton()
                        TextButton(
                            onClick = onLicenses,
                            modifier = Modifier.heightIn(min = CutSizes.touchTarget),
                        ) { Text("오픈소스 라이선스") }
                    }
                }
            }
        }

        NavigationBar(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            containerColor = palette.surface,
            tonalElevation = 0.dp,
        ) {
            val icons = listOf(
                CutIcons.Home, CutIcons.Projects, CutIcons.Tools, CutIcons.Settings,
            )
            val labels = listOf("홈", "프로젝트", "도구", "설정")
            labels.forEachIndexed { index, label ->
                NavigationBarItem(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    icon = {
                        Icon(
                            icons[index],
                            contentDescription = null,
                            modifier = Modifier.size(CutSizes.icon),
                        )
                    },
                    label = { Text(label) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = palette.accent,
                        selectedTextColor = palette.secondaryAccent,
                        indicatorColor = palette.accentMuted,
                        unselectedIconColor = palette.mutedText,
                        unselectedTextColor = palette.mutedText,
                    ),
                )
            }
        }
    }
}

@Composable
private fun StudioModes(
    onQuick: () -> Unit,
    onBatch: () -> Unit,
    onSelect: () -> Unit,
    onExtraction: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CutSpacing.md),
    ) {
        StudioModeCard(
            "빠른 자르기", "영상 1개 · 필요한 구간만 빠르게 남기기",
            CutIcons.Trim, onClick = onQuick,
        )
        StudioModeCard(
            "상세 편집", "자르기·분할·순서 변경을 자유롭게",
            CutIcons.Detailed, onClick = onSelect,
        )
        StudioModeCard(
            "일괄 편집", "여러 영상 · 같은 설정으로 한 번에",
            CutIcons.Batch, onClick = onBatch,
        )
        StudioModeCard(
            "여러 구간 추출", "영상 1개 · 여러 장면을 각각 MP4로 저장",
            CutIcons.Segments, onClick = onExtraction,
        )
    }
}

@Composable
private fun StudioModeCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val palette = LocalCutPalette.current
    Surface(
        onClick = onClick,
        shape = CutShape,
        color = palette.surface,
        contentColor = palette.primaryText,
        border = BorderStroke(1.dp, palette.border),
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
    ) {
        Row(
            Modifier.padding(CutSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CutSpacing.md),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(CutSizes.icon),
                tint = palette.secondaryAccent,
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(CutSpacing.xs),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.primaryText,
                )
                Text(
                    subtitle,
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                CutIcons.Forward,
                contentDescription = null,
                modifier = Modifier.size(CutSizes.icon),
                tint = palette.secondaryText,
            )
        }
    }
}

@Composable
private fun StudioSettingsPanel(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalCutPalette.current
    Column(
        Modifier.fillMaxWidth()
            .background(palette.surface, CutPanelShape)
            .border(1.dp, palette.border, CutPanelShape)
            .padding(CutSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(CutSpacing.md),
    ) {
        Text(
            title,
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium,
        )
        content()
    }
}

@Composable
private fun StudioSettings(
    settings: QuickTrimSettings,
    dateMode: SaveDateMode,
    themeMode: QuickCutThemeMode,
    onThemeModeChange: (QuickCutThemeMode) -> Unit,
    onPreferences: () -> Unit,
) {
    val palette = LocalCutPalette.current
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CutSpacing.md),
    ) {
        StudioSettingsPanel("화면") {
            CutChoiceDropdown(
                label = "테마",
                selected = themeMode,
                options = listOf(
                    CutChoice(QuickCutThemeMode.SYSTEM, "시스템 설정"),
                    CutChoice(QuickCutThemeMode.LIGHT, "라이트"),
                    CutChoice(QuickCutThemeMode.DARK, "다크"),
                ),
                onSelected = onThemeModeChange,
            )
        }
        StudioSettingsPanel("편집 기본값") {
            if (LocalDensity.current.fontScale >= 1.6f) {
                Text(
                    "마지막 사용 설정",
                    color = palette.primaryText,
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(
                    onClick = onPreferences,
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = CutSizes.touchTarget),
                    shape = CutShape,
                ) { Text("설정 변경") }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "마지막 사용 설정",
                        modifier = Modifier.weight(1f),
                        color = palette.primaryText,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(
                        onClick = onPreferences,
                        modifier = Modifier.heightIn(min = CutSizes.touchTarget),
                        shape = CutShape,
                    ) { Text("설정 변경") }
                }
            }
            Text(
                "앞 " + (settings.leadingMs / 1_000.0) + "초 삭제 · 뒤 " +
                    (settings.trailingMs / 1_000.0) + "초 삭제",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (settings.muteAudio) "음소거" else "소리 유지",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        StudioSettingsPanel("저장") {
            Text(
                "결과 영상 날짜",
                color = palette.primaryText,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (dateMode == SaveDateMode.CURRENT) "현재 날짜로 저장"
                else "원본 날짜 유지",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "원본 영상 처리는 저장할 때 확인합니다.",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
