package com.hebi.quickcut.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class WelcomePage(val title: String, val detail: String)

private val welcomePages = listOf(
    WelcomePage("빠르고 간편한\\n영상 편집", "복잡한 과정 없이\\n원하는 구간만 자르고 간단하게 편집해 보세요."),
    WelcomePage("필요한 도구만", "빠른 자르기, 상세 편집, 일괄 편집을\\n원하는 작업에 맞게 선택하세요."),
    WelcomePage("안전하게 저장", "편집 결과를 확인한 뒤 저장하고\\n원본 처리 방식도 직접 선택하세요."),
)

@Composable
internal fun QuickCutWelcome(onFinish: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = welcomePages[index]
    Column(
        modifier = Modifier.fillMaxSize().background(CutColors.background)
            .padding(horizontal = CutSpacing.xl, vertical = CutSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(20.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CutSpacing.xl)) {
            Box(
                modifier = Modifier.size(128.dp)
                    .background(CutColors.accentMuted, CutPanelShape),
                contentAlignment = Alignment.Center,
            ) {
                val icon = when (index) {
                    0 -> Icons.Outlined.ContentCut
                    1 -> Icons.Outlined.PlayCircleOutline
                    else -> Icons.Outlined.VideoLibrary
                }
                Icon(icon, contentDescription = null, tint = CutColors.secondaryAccent,
                    modifier = Modifier.size(80.dp))
            }
            Text(page.title.replace("\\n", "\n"), color = CutColors.primaryText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(page.detail.replace("\\n", "\n"), color = CutColors.secondaryText,
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CutSpacing.xl)) {
            Row(horizontalArrangement = Arrangement.spacedBy(CutSpacing.sm),
                verticalAlignment = Alignment.CenterVertically) {
                repeat(welcomePages.size) { i ->
                    Box(Modifier.size(if (i == index) 9.dp else 7.dp)
                        .background(if (i == index) CutColors.accent else CutColors.mutedText, CircleShape))
                }
            }
            Button(
                onClick = { if (index == welcomePages.lastIndex) onFinish() else index++ },
                modifier = Modifier.fillMaxWidth().height(CutSizes.primaryButton),
                shape = CutShape,
            ) {
                Text(if (index == welcomePages.lastIndex) "시작하기" else "다음",
                    color = Color.White)
            }
        }
    }
}
