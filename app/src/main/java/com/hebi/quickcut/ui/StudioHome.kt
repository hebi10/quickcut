package com.hebi.quickcut.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
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
        Image(painterResource(R.drawable.quickcut_mark), contentDescription = null,
            modifier = Modifier.size(44.dp))
        Column {
            Text("QuickCut", style = MaterialTheme.typography.titleLarge,
                color = CutColors.primaryText, fontWeight = FontWeight.Bold)
            Text("POCKET CUT STUDIO", color = CutColors.mutedText,
                fontSize = 10.sp, letterSpacing = 1.4.sp)
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
    BoxWithConstraints(Modifier.fillMaxSize().background(CutColors.background)) {
        val expanded = maxWidth >= 720.dp
        val showArt = maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.3f
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (expanded) 32.dp else 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 1000.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(22.dp)) {
                StudioBrand()
                if (expanded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.Top) {
                        StudioWelcome(showArt, Modifier.weight(1f))
                        StudioModes(onQuick, onBatch, onSelect, Modifier.weight(1f))
                    }
                } else {
                    StudioWelcome(showArt)
                    StudioModes(onQuick, onBatch, onSelect)
                }
                if (canRestore) {
                    OutlinedButton(onClick = onRestore, shape = CutShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(Icons.Outlined.History, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("이전 편집 이어서 진행")
                    }
                }
                StudioSettings(quickSettings, dateMode, onPreferences)
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    ExportDiagnosticButton()
                    TextButton(onClick = onLicenses, shape = CutShape,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("오픈소스 라이선스", color = CutColors.mutedText,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioWelcome(showArt: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()
        .background(Brush.linearGradient(listOf(CutColors.panel, CutColors.surface)), CutPanelShape)
        .border(1.dp, CutColors.border, CutPanelShape).padding(20.dp)) {
        Text("나만의 작은 편집실", color = CutColors.secondaryAccent,
            style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("좋은 장면만,\n가볍게 남기세요.", color = CutColors.primaryText,
                    style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                Text("필요한 구간만 빠르게 잘라 저장하세요.",
                    color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            if (showArt) {
                Image(painterResource(R.drawable.quickcut_studio), contentDescription = null,
                    modifier = Modifier.width(106.dp).height(100.dp), contentScale = ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun StudioModes(onQuick: () -> Unit, onBatch: () -> Unit,
    onSelect: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioModeCard("빠른 자르기 · 영상 1개", "앞뒤 구간을 덜어내고 간편하게",
            Icons.Outlined.ContentCut, primary = true, onClick = onQuick)
        StudioModeCard("일괄 편집 · 여러 영상", "내 프리셋으로 여러 영상을 한 번에",
            Icons.Outlined.VideoLibrary, onClick = onBatch)
        StudioModeCard("상세 편집 (분할·연결·순서 변경)", "구간을 나누고 원하는 순서로",
            Icons.Outlined.Movie, onClick = onSelect)
    }
}

@Composable
private fun StudioModeCard(title: String, subtitle: String, icon: ImageVector,
    primary: Boolean = false, onClick: () -> Unit) {
    val foreground = if (primary) CutColors.onAccent else CutColors.primaryText
    Surface(onClick = onClick, shape = CutPanelShape,
        color = if (primary) CutColors.accent else CutColors.surface,
        contentColor = foreground,
        border = if (primary) null else BorderStroke(1.dp, CutColors.border),
        modifier = Modifier.fillMaxWidth().heightIn(min = 86.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(25.dp),
                tint = if (primary) foreground else CutColors.accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = if (primary) CutColors.onAccent.copy(alpha = 0.8f)
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("마지막 사용 설정", modifier = Modifier.weight(1f),
                color = CutColors.primaryText, style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onPreferences, shape = CutShape,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("설정 변경") }
        }
        Text("앞 " + (settings.leadingMs / 1_000.0) + "초 삭제 · 뒤 " +
            (settings.trailingMs / 1_000.0) + "초 삭제",
            color = CutColors.secondaryText, style = MaterialTheme.typography.bodyMedium)
        Text((if (settings.muteAudio) "음소거" else "소리 유지") + " · " +
            (if (dateMode == SaveDateMode.CURRENT) "현재 날짜 저장" else "원본 날짜 유지"),
            color = CutColors.mutedText, style = MaterialTheme.typography.bodySmall)
    }
}
