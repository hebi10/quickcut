package com.hebi.quickcut.ui

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class GuideStep(val title: String, val description: String)
internal data class GuideContent(
    val id: String,
    val title: String,
    val introduction: String,
    val steps: List<GuideStep>,
    val version: Int = 1,
)

internal object QuickCutGuides {
    val all = listOf(
        GuideContent("home", "홈", "어떤 영상을 편집할지 선택하는 첫 화면입니다.", listOf(
            GuideStep("빠른 자르기", "영상 1개에서 원하는 앞뒤 구간만 남기고 새 MP4로 저장합니다."),
            GuideStep("다른 편집 도구", "도구 탭에서 상세 편집, 일괄 편집, 여러 구간 추출을 선택할 수 있습니다."),
            GuideStep("프로젝트와 영상", "프로젝트 저장은 편집 내용을 보관하고, 영상 저장은 갤러리에 MP4를 만듭니다."),
        )),
        GuideContent("projects", "프로젝트", "나중에 다시 편집할 내용을 관리합니다.", listOf(
            GuideStep("저장 프로젝트", "상세 편집 화면의 ‘편집 내용 저장’으로 만든 프로젝트를 다시 열 수 있습니다."),
            GuideStep("자동 복원", "‘이전 편집 이어서 진행’은 앱이 보관한 최근 편집 세션을 되살립니다. 수동 저장 프로젝트와는 다릅니다."),
            GuideStep("원본 접근", "프로젝트는 원본 영상을 통째로 백업하지 않습니다. 원본 파일에 접근할 수 있어야 편집을 이어갈 수 있습니다."),
        )),
        GuideContent("tools", "편집 도구", "작업에 맞는 편집 방식을 고릅니다.", listOf(
            GuideStep("한 영상 자르기", "빠른 자르기는 한 영상의 시작과 끝을 조절합니다."),
            GuideStep("여러 클립 편집", "상세 편집은 분할·순서·크롭·음성·텍스트를 조절합니다."),
            GuideStep("반복 작업", "일괄 편집은 여러 영상에 동일 설정을 적용하며, 여러 구간 추출은 한 영상에서 개별 MP4를 만듭니다."),
        )),
        GuideContent("gallery", "영상 선택", "편집할 영상을 고르는 화면입니다.", listOf(
            GuideStep("영상 확인", "썸네일 아래의 파일명과 재생 길이로 영상을 구별합니다."),
            GuideStep("선택 순서", "선택 표시의 번호는 현재 선택한 순서입니다. 다시 누르면 선택이 해제됩니다."),
            GuideStep("편집 시작", "원하는 영상을 선택한 후 편집 버튼으로 이동합니다."),
        )),
        GuideContent("quick", "빠른 자르기", "한 영상에서 남길 구간을 지정합니다.", listOf(
            GuideStep("앞부분·뒷부분 삭제", "입력한 초만큼 영상의 앞이나 뒤를 제거합니다. 결과 길이를 확인하세요."),
            GuideStep("정밀 조절", "슬라이더나 시간 입력, ±1초 버튼으로 남길 구간을 조절합니다."),
            GuideStep("영상 저장", "결과 길이와 원본 처리 설정을 확인한 후 MP4를 저장합니다. 편집 내용 보관은 별도의 ‘편집 내용 저장’입니다."),
        )),
        GuideContent("editor", "상세 편집", "클립을 선택하고 도구로 편집합니다.", listOf(
            GuideStep("타임라인 선택", "타임라인에서 클립을 누른 뒤 자르기·분할·삭제·순서 변경 등을 사용하세요."),
            GuideStep("도구 더 보기", "작은 화면에서는 아래 도구 줄을 좌우로 밀어 숨은 기능을 찾을 수 있습니다. 사용할 수 없는 도구는 선택 상태나 소리 유무를 확인하세요."),
            GuideStep("저장 구분", "상단 ‘편집 내용 저장’은 나중에 편집할 프로젝트를 보관합니다. ‘영상 저장’은 갤러리용 MP4를 만듭니다."),
        )),
        GuideContent("batch", "일괄 편집", "여러 영상을 같은 방식으로 편집합니다.", listOf(
            GuideStep("설정과 영상 선택", "자르기 방식·시간·음소거를 설정하고 여러 영상을 추가합니다."),
            GuideStep("예상 결과 검토", "파일별 남을 길이와 저장 방식을 확인하고, 제외하거나 추천 구간을 직접 선택할 수 있습니다."),
            GuideStep("원본 보호", "원본은 기본적으로 유지됩니다. 휴지통이나 삭제는 저장 성공 후 Android 승인 절차를 따릅니다."),
        )),
        GuideContent("extraction", "여러 구간 추출", "한 영상에서 여러 장면을 따로 저장합니다.", listOf(
            GuideStep("원본 선택", "먼저 하나의 영상을 선택하고 구간을 추가합니다."),
            GuideStep("구간별 확인", "각 구간의 시작·종료 시간과 미리보기를 확인하세요. 개별 MP4로 저장됩니다."),
            GuideStep("저장·복구", "중단되었더라도 확인된 완료 파일은 유지하고 나머지는 다시 검토합니다."),
        )),
        GuideContent("crop", "크롭", "화면에 남길 영역을 조절합니다.", listOf(
            GuideStep("비율 선택", "원하는 화면 비율을 정하고 미리보기에서 잘리는 영역을 확인합니다."),
            GuideStep("세부 위치", "필요할 때만 세부 위치·확대 설정을 펼쳐 조절합니다."),
            GuideStep("적용과 취소", "적용하면 선택 클립의 편집 내용에 반영됩니다. 취소하면 이번 조절을 반영하지 않습니다."),
        )),
        GuideContent("audio", "오디오", "클립의 소리 크기와 페이드를 조절합니다.", listOf(
            GuideStep("소리 확인", "원본에 오디오가 있는 클립을 선택해야 세부 오디오 조절을 사용할 수 있습니다."),
            GuideStep("볼륨과 페이드", "기본 볼륨을 먼저 조절하고, 필요한 경우 페이드 인·아웃을 펼칩니다."),
            GuideStep("적용과 취소", "적용 전 미리보기를 확인하고, 변경하지 않으려면 취소를 누릅니다."),
        )),
        GuideContent("text", "텍스트 편집", "영상에 직접 입력한 문구를 표시합니다.", listOf(
            GuideStep("내용", "표시할 텍스트를 직접 입력합니다. 음성 자동 인식 자막 기능은 아닙니다."),
            GuideStep("스타일과 시간", "스타일 탭에서 색상·위치를, 표시 시간 탭에서 시작·끝 구간을 조절합니다."),
            GuideStep("적용과 취소", "적용하면 텍스트가 선택 클립에 반영됩니다. 취소하면 현재 조절을 버립니다."),
        )),
        GuideContent("save", "영상 저장", "프로젝트 보관과 다른 MP4 출력 과정입니다.", listOf(
            GuideStep("출력 설정", "저장하면 갤러리에 새로운 MP4 영상이 만들어집니다. 원본 처리 방식을 먼저 확인하세요."),
            GuideStep("정확한 구간", "‘선택한 구간 그대로 저장’은 요청 시간을 지키며 필요한 경우 변환합니다."),
            GuideStep("추천 구간", "‘추천 구간으로 빠르게 저장’은 시작·끝 시간이 바뀔 수 있으므로 실제 구간을 확인하고 동의하세요."),
        )),
        GuideContent("recovery", "복구와 오류 제보", "이전 편집이나 저장 실패를 안전하게 처리합니다.", listOf(
            GuideStep("저장 결과 확인", "완료 여부가 불확실한 결과는 자동으로 중복 등록하지 않습니다. 먼저 ‘다시 확인’을 사용하세요."),
            GuideStep("원본 다시 연결", "원본 접근이 끊겼다면 같은 영상을 다시 선택할 수 있습니다. 확인 전 편집 기록은 유지됩니다."),
            GuideStep("오류 제보", "‘최근 작업 진단’에서 기록을 확인하고 메일 앱을 통해 직접 전송하거나 복사할 수 있습니다."),
        )),
    )
    fun find(id: String): GuideContent? = all.firstOrNull { it.id == id }
}

internal object QuickCutGuideState {
    private const val PREFS = "quickcut_screen_guides"
    private fun key(guide: GuideContent) = "seen_${guide.id}_v${guide.version}"

    fun hasSeen(context: Context, id: String): Boolean {
        val guide = QuickCutGuides.find(id) ?: return true
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key(guide), false)
    }

    fun markSeen(context: Context, id: String) {
        val guide = QuickCutGuides.find(id) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key(guide), true).apply()
    }
}

@Composable
internal fun FirstVisitGuideBanner(
    screenId: String,
    onHelp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val guide = QuickCutGuides.find(screenId) ?: return
    val context = LocalContext.current
    var dismissed by rememberSaveable(screenId) {
        mutableStateOf(QuickCutGuideState.hasSeen(context, screenId))
    }
    if (dismissed) return

    val palette = LocalCutPalette.current
    Surface(
        modifier = modifier.fillMaxWidth().testTag("first-guide-$screenId"),
        color = palette.surface,
        shape = CutShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("처음 방문: ${guide.title}", color = palette.primaryText,
                style = MaterialTheme.typography.labelMedium)
            Text(guide.introduction, color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    QuickCutGuideState.markSeen(context, screenId)
                    dismissed = true
                    onHelp(screenId)
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("사용 방법")
                }
                TextButton(onClick = {
                    QuickCutGuideState.markSeen(context, screenId)
                    dismissed = true
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("건너뛰기")
                }
            }
        }
    }
}

@Composable
internal fun ScreenGuideDialog(screenId: String, onDismiss: () -> Unit) {
    val guide = QuickCutGuides.find(screenId) ?: return
    val palette = LocalCutPalette.current
    val context = LocalContext.current
    var page by rememberSaveable(screenId) { mutableIntStateOf(0) }
    val step = guide.steps[page]
    val finish = {
        QuickCutGuideState.markSeen(context, screenId)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = finish,
        title = { Text(guide.title, color = palette.primaryText) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${page + 1} / ${guide.steps.size}",
                    color = palette.secondaryText, style = MaterialTheme.typography.labelMedium)
                Text(step.title, color = palette.primaryText,
                    style = MaterialTheme.typography.titleMedium)
                Text(step.description, color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("guide-step-description"))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (page == guide.steps.lastIndex) finish() else page++
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("guide-next"),
                shape = CutShape,
            ) { Text(if (page == guide.steps.lastIndex) "완료" else "다음") }
        },
        dismissButton = {
            TextButton(onClick = finish, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("건너뛰기")
            }
        },
        containerColor = palette.panel,
        shape = CutDialogShape,
    )
}
