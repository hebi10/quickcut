package com.hebi.quickcut.batch

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.ui.QuickCutTheme
import java.io.File
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test

class BatchRecoveryUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var model: BatchViewModel? = null
    private lateinit var oldDraft: com.hebi.quickcut.settings.BatchDraftSettings
    private lateinit var oldDate: SaveDateMode

    @Before fun captureSettings() {
        com.hebi.quickcut.settings.QuickCutSettings(context).let {
            oldDraft = it.loadBatch(); oldDate = it.loadSaveDate()
        }
    }

    @After fun clear() {
        compose.runOnIdle { model?.let { ViewModelStore().apply { put("test", it) }.clear() } }
        BatchSessionStore(context).clear()
        com.hebi.quickcut.settings.QuickCutSettings(context).apply {
            saveBatch(oldDraft); saveSaveDate(oldDate)
        }
    }

    @Test fun emptyCorruptJournalShowsPersistentRecoveryActionAndBlocksStart() {
        BatchSessionStore(context).clear()
        val file = File(context.filesDir, "quickcut_batch_session_v1.json").apply { writeText("{truncated") }
        val vm = compose.runOnIdle { BatchViewModel(context.applicationContext as Application) }.also { model = it }
        compose.runOnIdle { vm.clearNotification() }
        show(vm)
        compose.onNodeWithTag("batch-recovery-retry").assertExists().performClick()
        compose.waitUntil(10_000L) { !vm.isRecovering }
        compose.runOnIdle {
            check(vm.hasUnresolvedRecovery)
            check(file.readText() == "{truncated")
            vm.clearNotification()
        }
        compose.onNodeWithText("일괄 편집 및 저장 시작").assertIsNotEnabled()
        // A user-fixed/removed journal can be rechecked through the same visible control.
        BatchSessionStore(context).clear()
        compose.onNodeWithTag("batch-recovery-retry").performClick()
        compose.waitUntil(10_000L) { !vm.isRecovering && !vm.hasUnresolvedRecovery }
        compose.runOnIdle { vm.clearNotification() }
        compose.onNodeWithTag("batch-recovery-retry").assertDoesNotExist()
    }

    @Test fun fileDiagnosticLinkRetainsOperationTargetWhenRecentHistoryIsMissing() {
        val id = java.util.UUID.randomUUID().toString()
        BatchSessionStore(context).save(BatchSessionSnapshot(listOf(BatchFile(
            "content://missing/source/1", "진단 연결 검사", status = BatchFileStatus.FAILED,
            diagnosticId = id)), null, SaveDateMode.CURRENT, BatchRunState.FINISHED))
        val vm = compose.runOnIdle { BatchViewModel(context.applicationContext as Application) }.also { model = it }
        compose.waitUntil(10_000L) { !vm.isRecovering }
        compose.runOnIdle { vm.clearNotification() }
        show(vm)
        compose.onNodeWithText("저장 진단").performScrollTo().performClick()
        compose.onNodeWithText("이 작업의 진단은 보관 개수를 넘었거나 기록을 읽을 수 없어 표시할 수 없습니다.\n")
            .assertExists()
    }

    private fun show(vm: BatchViewModel) {
        compose.setContent { QuickCutTheme { BatchScreen(vm, {}, {}, { _, _, _ -> }) } }
    }
}
