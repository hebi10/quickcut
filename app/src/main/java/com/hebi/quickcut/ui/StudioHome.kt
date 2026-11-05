package com.hebi.quickcut.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hebi.quickcut.R
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickTrimSettings

@Composable
internal fun StudioBrand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        QuickCutLogo(size = CutSizes.touchTarget)
        Column {
            Text("QuickCut", style = MaterialTheme.typography.titleLarge,
                color = CutColors.primaryText, fontWeight = FontWeight.Bold)
            Text("빠르고 간편한 영상 편집", color = CutColors.secondaryText,
                fontSize = 11.sp, letterSpacing = 0.2.sp)
        }
    }
}

@Composable
internal fun StudioStartScreen(
    onQuick: () -> Unit,
    onSelect: () -> Unit,
    onBatch: () -> Unit,
    canRestore: Boolean,
    onRestore: () -> Unit,
    quickSettings: QuickTrimSettings,
    dateMode: SaveDateMode,
    onPreferences: () -> Unit,
    onLicenses: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(CutColors.background)) {
        Box(Modifier.weight(1f)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = CutSpacing.lg, vertical = CutSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(CutSpacing.xl),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    StudioBrand()
                    if (selectedTab != 3) {
                        TextButton(onClick = { selectedTab = 3 },
                            modifier = Modifier.heightIn(min = CutSizes.touchTarget)) {
                            Icon(Icons.Outlined.Settings, contentDescription = "설정",
                                tint = CutColors.secondaryAccent)
                        }
                    }
                }
                when (selectedTab) {
                    0 -> {
                        Text("영상 편집을 시작하세요", color = CutColors.primaryText,
                            style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = onQuick, shape = CutShape,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 92.dp)) {
                            Icon(Icons.Outlined.ContentCut, contentDescription = null,
                                modifier = Modifier.size(CutSizes.icon))
                            Spacer(Modifier.width(CutSpacing.sm))
                            Column {
                                Text("+ 영상 편집 시작하기",
                                    style = MaterialTheme.typography.titleMedium)
                                Text("영상 선택 후 빠른 자르기",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("내 프로젝트", color = CutColors.primaryText,
                            style = MaterialTheme.typography.titleMedium)
                        if (canRestore) {
                            OutlinedButton(onClick = onRestore, shape = CutShape,
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = 82.dp)) {
                                Icon(Icons.Outlined.History, contentDescription = null)
                                Spacer(Modifier.width(CutSpacing.md))
                                Text("이전 편집 이어서 진행")
                            }
                        } else {
                            Text("저장된 편집 작업이 없습니다. 새 영상을 선택해 주세요.",
                                modifier = Modifier.fillMaxWidth()
                                    .background(CutColors.surface, CutPanelShape)
                                    .padding(CutSpacing.xl),
                                style = MaterialTheme.typography.bodyMedium,
                                color = CutColors.secondaryText)
                        }
                        Text("편집 도구", color = CutColors.primaryText,
                            style = MaterialTheme.typography.titleMedium)
                        StudioModes(onQuick, onBatch, onSelect)
                    }
                    1 -> {
                        Text("내 프로젝트", style = MaterialTheme.typography.headlineSmall,
                            color = CutColors.primaryText)
                        if (canRestore) {
                            OutlinedButton(onClick = onRestore,
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = 80.dp), shape = CutShape) {
                                Icon(Icons.Outlined.History, contentDescription = null)
                                Spacer(Modifier.width(CutSpacing.sm))
                                Text("이전 편집 이어서 진행")
                            }
                        } else {
                            Text("현재 복구할 편집 작업이 없습니다.",
                                color = CutColors.secondaryText)
                        }
                        Text("여러 프로젝트의 영구 저장 및 목록 관리는 3단계에서 구현합니다.",
                            color = CutColors.mutedText,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    2 -> {
                        Text("편집 도구", color = CutColors.primaryText,
                            style = MaterialTheme.typography.headlineSmall)
                        StudioModes(onQuick, onBatch, onSelect)
                    }
                    3 -> {
                        Text("설정", color = CutColors.primaryText,
                            style = MaterialTheme.typography.headlineSmall)
                        StudioSettings(quickSettings, dateMode, onPreferences)
                        ExportDiagnosticButton()
                        PrivacyPolicyButton()
                        TextButton(onClick = onLicenses,
                            modifier = Modifier.heightIn(min = CutSizes.touchTarget)) {
                            Text("오픈소스 라이선스")
                        }
                    }
                }
            }
        }
        NavigationBar(containerColor = CutColors.surface,
            tonalElevation = 0.dp) {
            val icons = listOf(
                Icons.Outlined.Home, Icons.Outlined.FolderOpen,
                Icons.Outlined.Build, Icons.Outlined.Settings,
            )
            val labels = listOf("홈", "프로젝트", "도구", "설정")
            labels.forEachIndexed { index, label ->
                NavigationBarItem(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    icon = {
                        Icon(icons[index], contentDescription = null,
                            modifier = Modifier.size(CutSizes.icon))
                    },
                    label = { Text(label) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CutColors.accent,
                        selectedTextColor = CutColors.secondaryAccent,
                        indicatorColor = CutColors.accentMuted,
                        unselectedIconColor = CutColors.mutedText,
                        unselectedTextColor = CutColors.mutedText,
                    ),
                )
            }
        }
    }
}

@Composable
private fun StudioWelcome(showArt: Boolean, onStart: () -> Unit, modifier: Modifier = Modifier,
    compactHeadline: Boolean = false) {
    Column(modifier.fillMaxWidth()
        .background(
            Brush.horizontalGradient(
                listOf(CutColors.brandDeep.copy(alpha = 0.86f), CutColors.panel, CutColors.surface),
            ),
            CutPanelShape,
        )
        .border(1.dp, CutColors.brandLight.copy(alpha = 0.55f), CutPanelShape).padding(20.dp)) {
        Text("FAST · SIMPLE · FOCUSED", color = CutColors.secondaryAccent,
            style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("영상은 빠르게,\n편집은 간단하게.", color = CutColors.primaryText,
                    style = if (compactHeadline) MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp)
                        else MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                Text("필요한 장면만 자르고 안전하게 저장하세요.",
                    color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            if (showArt) {
                Image(
                    painter = painterResource(R.drawable.quickcut_studio),
                    contentDescription = null,
                    modifier = Modifier.width(132.dp).height(100.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(CutColors.secondaryAccent),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onStart,
            shape = CutShape,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        ) {
            if (LocalDensity.current.fontScale <= 1.4f) {
                Icon(Icons.Outlined.Movie, contentDescription = null,
                    modifier = Modifier.size(CutSizes.icon))
                Spacer(Modifier.width(8.dp))
            }
            Text("영상 편집 시작하기", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StudioModes(onQuick: () -> Unit, onBatch: () -> Unit,
    onSelect: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("어떤 영상을 편집할까요?", style = MaterialTheme.typography.titleMedium,
            color = CutColors.primaryText)
        StudioModeCard("빠른 자르기", "영상 1개 · 필요한 구간만 빠르게 남기기",
            Icons.Outlined.ContentCut, primary = true, onClick = onQuick)
        StudioModeCard("일괄 편집", "여러 영상 · 같은 설정으로 한 번에",
            Icons.Outlined.VideoLibrary, onClick = onBatch)
        StudioModeCard("상세 편집", "자르기·분할·순서 변경을 자유롭게",
            Icons.Outlined.Movie, onClick = onSelect)
    }
}

@Composable
private fun StudioModeCard(title: String, subtitle: String, icon: ImageVector,
    primary: Boolean = false, onClick: () -> Unit) {
    val foreground = if (primary) CutColors.onAccent else CutColors.primaryText
    Surface(onClick = onClick, shape = CutShape,
        color = if (primary) CutColors.accent else CutColors.surface,
        contentColor = foreground,
        border = if (primary) null else BorderStroke(1.dp, CutColors.border),
        modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(25.dp),
                tint = if (primary) foreground else CutColors.accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = if (primary) CutColors.onAccent
                    else CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = foreground)
        }
    }
}

@Composable
private fun StudioSettings(settings: QuickTrimSettings, dateMode: SaveDateMode,
    onPreferences: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(CutColors.surface, CutPanelShape)
        .border(1.dp, CutColors.border, CutPanelShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (LocalDensity.current.fontScale >= 1.6f) {
            Text("마지막 사용 설정", color = CutColors.primaryText,
                style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onPreferences, shape = CutShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("설정 변경") }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("마지막 사용 설정", modifier = Modifier.weight(1f),
                    color = CutColors.primaryText, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onPreferences, shape = CutShape,
                    modifier = Modifier.heightIn(min = CutSizes.touchTarget)) { Text("설정 변경") }
            }
        }
        Text("앞 " + (settings.leadingMs / 1_000.0) + "초 삭제 · 뒤 " +
            (settings.trailingMs / 1_000.0) + "초 삭제",
            color = CutColors.secondaryText, style = MaterialTheme.typography.bodyMedium)
        Text((if (settings.muteAudio) "음소거" else "소리 유지") + " · " +
            (if (dateMode == SaveDateMode.CURRENT) "현재 날짜 저장" else "원본 날짜 유지"),
            color = CutColors.mutedText, style = MaterialTheme.typography.bodySmall)
    }
}
