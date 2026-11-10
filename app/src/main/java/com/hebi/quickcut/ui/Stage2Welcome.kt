package com.hebi.quickcut.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class WelcomePage(val title: String, val detail: String)

private val welcomePages = listOf(
    WelcomePage("빠르고 간편한\\n영상 편집", "복잡한 과정 없이\\n원하는 구간만 자르고 간단하게 편집해 보세요."),
    WelcomePage("필요한 도구만", "빠른 자르기, 상세 편집, 일괄 편집을\\n원하는 작업에 맞게 선택하세요."),
    WelcomePage("안전하게 저장", "편집 결과를 확인한 뒤 저장하고\\n원본 처리 방식도 직접 선택하세요."),
)

@Composable
internal fun QuickCutWelcome(onFinish: () -> Unit) {
    val palette = LocalCutPalette.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = welcomePages[index]
    val compactArtwork = LocalDensity.current.fontScale >= 1.6f
    val contentScroll = rememberScrollState()
    LaunchedEffect(index) { contentScroll.scrollTo(0) }
    Column(
        modifier = Modifier.fillMaxSize().background(palette.background)
            .padding(horizontal = CutSpacing.xl, vertical = CutSpacing.lg),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = CutSizes.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CutSpacing.sm)) {
            QuickCutLogo(size = 32.dp)
            Text("QuickCut", Modifier.weight(1f), color = palette.primaryText,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${index + 1} / ${welcomePages.size}", color = palette.secondaryText,
                style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(contentScroll)
            .padding(vertical = CutSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(CutSpacing.lg)) {
            WelcomeFrameDiagram(index, compactArtwork)
            Text(page.title.replace("\\n", "\n"), color = palette.primaryText,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 32.sp),
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Start)
            Text(page.detail.replace("\\n", "\n"), color = palette.secondaryText,
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Start)
        }
        Column(Modifier.fillMaxWidth().padding(top = CutSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CutSpacing.md)) {
            Row(Modifier.semantics {
                contentDescription = "사용 가이드 진행"
                stateDescription = "${index + 1} / ${welcomePages.size}단계"
            }, horizontalArrangement = Arrangement.spacedBy(CutSpacing.sm),
                verticalAlignment = Alignment.CenterVertically) {
                repeat(welcomePages.size) { i ->
                    Box(Modifier.size(if (i == index) 9.dp else 7.dp)
                        .background(if (i == index) palette.accent else palette.mutedText, CircleShape))
                }
            }
            Button(
                onClick = { if (index == welcomePages.lastIndex) onFinish() else index++ },
                modifier = Modifier.fillMaxWidth().heightIn(min = CutSizes.primaryButton),
                contentPadding = PaddingValues(horizontal = CutSpacing.lg, vertical = CutSpacing.lg),
                shape = CutShape,
            ) {
                Text(if (index == welcomePages.lastIndex) "시작하기" else "다음",
                    color = palette.onAccent)
            }
        }
    }
}

/** A native, decorative explanation of existing editing steps, without simulated controls. */
@Composable
private fun WelcomeFrameDiagram(page: Int, compact: Boolean) {
    val palette = LocalCutPalette.current
    Box(Modifier.fillMaxWidth().height(if (compact) 104.dp else 168.dp)
        .background(palette.panel, CutPanelShape).testTag("quickcut-onboarding-artwork"),
        contentAlignment = Alignment.Center) {
        if (page == 1) {
            Row(Modifier.fillMaxWidth().padding(horizontal = CutSpacing.xl),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                listOf(CutIcons.Trim, CutIcons.Detailed, CutIcons.Batch).forEachIndexed { i, image ->
                    if (i > 0) Box(Modifier.width(1.dp).height(40.dp).background(palette.border))
                    Icon(image, null, Modifier.size(CutSizes.icon), tint = palette.primaryText)
                }
            }
        } else {
            Column(Modifier.widthIn(max = 220.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(CutSpacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CutSpacing.md)) {
                    Box(Modifier.weight(1f).height(if (compact) 48.dp else 76.dp)
                        .border(1.dp, palette.primaryText, CutSmallShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(CutSizes.icon), tint = palette.primaryText)
                    }
                    if (page == 2) Icon(Icons.Outlined.Check, null,
                        Modifier.size(CutSizes.icon), tint = palette.primaryText)
                }
                Row(Modifier.fillMaxWidth().height(10.dp).border(1.dp, palette.border, CutSmallShape)) {
                    repeat(if (page == 0) 3 else 5) { segment ->
                        Box(Modifier.weight(1f).fillMaxSize()
                            .background(if (segment == 1) palette.border else palette.accentMuted)
                            .border(.5.dp, palette.border))
                    }
                }
            }
        }
    }
}
