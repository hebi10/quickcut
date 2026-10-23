package com.hebi.quickcut.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.media.ExportDiagnosticHistory
import com.hebi.quickcut.media.ExportDiagnosticStore
import org.junit.Rule
import org.junit.Test

class ExportDiagnosticButtonInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun retainedFailureCanBeOpenedFromScrollableHistoryAndCopied() {
        val context = compose.activity
        val failed = ExportDiagnosticStore.begin(context, EditTimeline())
        failed.finish("실패", java.io.IOException("private-source.mp4"))
        repeat(ExportDiagnosticHistory.RECENT_LIMIT + 1) {
            ExportDiagnosticStore.begin(context, EditTimeline()).finish("완료")
        }
        compose.setContent { QuickCutTheme { ExportDiagnosticButton() } }
        compose.onNodeWithText("최근 저장 진단").performClick()
        compose.onNodeWithText("작업 ${failed.operationId.take(8)}", substring = true)
            .performScrollTo().performClick()
        compose.onNodeWithText("작업 ID: ${failed.operationId}", substring = true).assertIsDisplayed()
        compose.onNodeWithText("진단 복사").performClick()
        compose.onNodeWithText("복사됨").assertIsDisplayed()
    }

    @Test fun operationButtonOpensTheRequestedRecordAfterLaterSuccess() {
        val context = compose.activity
        val failed = ExportDiagnosticStore.begin(context, EditTimeline())
        failed.finish("실패")
        ExportDiagnosticStore.begin(context, EditTimeline()).finish("완료")
        compose.setContent { QuickCutTheme { ExportDiagnosticButton(failed.operationId, "작업 진단") } }
        compose.onNodeWithText("작업 진단").performClick()
        compose.onNodeWithText("작업 ID: ${failed.operationId}", substring = true).assertIsDisplayed()
    }
}
