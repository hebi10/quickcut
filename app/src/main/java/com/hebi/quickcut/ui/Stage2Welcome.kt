package com.hebi.quickcut.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class WelcomePage(val title: String, val detail: String)

private val welcomePages = listOf(
    WelcomePage("빠르고 간편한\n영상 편집", "복잡한 과정 없이\n원하는 구간만 자르고 간단하게 편집해 보세요."),
    WelcomePage("필요한 도구만", "빠른 자르기, 상세 편집, 일괄 편집을\n원하는 작업에 맞게 선택하세요."),
    WelcomePage("안전하게 저장", "편집 결과를 확인한 뒤 저장하고\n원본 처리 방식도 직접 선택하세요."),
)

@Composable
internal fun QuickCutWelcome(onFinish: () -> Unit) {
    val palette = LocalCutPalette.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = welcomePages[index]
    val compactArtwork = LocalDensity.current.fontScale >= 1.6f

    Column(Modifier.fillMaxSize().background(palette.background)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CutSpacing.xl, vertical = CutSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CutSpacing.xl),
        ) {
            Text(
                "QUICKCUT · ${index + 1} / ${welcomePages.size}",
                style = MaterialTheme.typography.labelMedium,
                color = palette.secondaryText,
            )
            Box(
                modifier = Modifier.fillMaxWidth()
                    .height(if (compactArtwork) 120.dp else 168.dp)
                    .background(palette.surface, CutShape)
                    .border(1.dp, palette.border, CutShape)
                    .testTag("quickcut-onboarding-artwork"),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CutSpacing.md),
                ) {
                    Box(
                        modifier = Modifier
                            .width(if (compactArtwork) 170.dp else 220.dp)
                            .height(if (compactArtwork) 66.dp else 94.dp)
                            .background(palette.panel, CutShape)
                            .border(1.dp, palette.border, CutShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when (index) {
                                0 -> CutIcons.Trim
                                1 -> CutIcons.Tools
                                else -> CutIcons.Save
                            },
                            contentDescription = null,
                            tint = palette.primaryText,
                            modifier = Modifier.size(if (compactArtwork) 32.dp else 40.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(CutSpacing.sm)) {
                        repeat(welcomePages.size) { step ->
                            Box(
                                Modifier.width(if (compactArtwork) 46.dp else 60.dp)
                                    .height(4.dp)
                                    .background(
                                        if (step == index) palette.accent else palette.border,
                                        CutSmallShape,
                                    ),
                            )
                        }
                    }
                }
            }
            Text(
                page.title,
                color = palette.primaryText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                page.detail,
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = CutSpacing.xl, vertical = CutSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CutSpacing.lg),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(CutSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(welcomePages.size) { step ->
                    Box(
                        Modifier.width(if (step == index) 20.dp else 8.dp)
                            .height(8.dp)
                            .background(
                                if (step == index) palette.accent else palette.mutedText,
                                CutSmallShape,
                            ),
                    )
                }
            }
            Button(
                onClick = { if (index == welcomePages.lastIndex) onFinish() else index++ },
                modifier = Modifier.fillMaxWidth().height(CutSizes.primaryButton),
                shape = CutShape,
            ) {
                Text(
                    if (index == welcomePages.lastIndex) "시작하기" else "다음",
                    color = palette.onAccent,
                )
            }
        }
    }
}
