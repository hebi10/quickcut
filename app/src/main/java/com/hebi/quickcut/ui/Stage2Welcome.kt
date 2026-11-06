package com.hebi.quickcut.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hebi.quickcut.R

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
    val compactArtwork = LocalDensity.current.fontScale >= 1.6f
    Column(
        modifier = Modifier.fillMaxSize().background(CutColors.background)
            .padding(horizontal = CutSpacing.xl, vertical = CutSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(20.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CutSpacing.xl)) {
            if (index == 0) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .height(if (compactArtwork) 142.dp else 188.dp)
                        .testTag("quickcut-onboarding-artwork"),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.quickcut_studio),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(CutColors.secondaryAccent),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(0.88f)
                            .height(if (compactArtwork) 122.dp else 166.dp),
                    )
                    QuickCutLogo(
                        size = if (compactArtwork) 44.dp else 56.dp,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .padding(end = CutSpacing.lg, bottom = CutSpacing.sm),
                    )
                }
            } else {
                Box(
                    modifier = Modifier.size(if (compactArtwork) 106.dp else 128.dp)
                        .background(CutColors.accentMuted, CutPanelShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (index == 1) Icons.Outlined.PlayCircleOutline else Icons.Outlined.VideoLibrary,
                        contentDescription = null,
                        tint = CutColors.secondaryAccent,
                        modifier = Modifier.size(if (compactArtwork) 68.dp else 80.dp),
                    )
                }
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
