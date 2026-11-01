package com.hebi.quickcut.batch

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.ui.QuickCutTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BatchPreferencesInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: IsolatedContext
    private lateinit var application: Application
    private lateinit var model: BatchViewModel
    private val models = mutableListOf<BatchViewModel>()
    private var approvalRequests = 0

    @Before fun setUp() {
        context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        application = object : Application() { init { attachBaseContext(context) } }
        model = newModel()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides application) {
                QuickCutTheme { BatchScreen(model, {}, {}, { _, _, _ -> approvalRequests++ }) }
            }
        }
    }

    @Test fun removingNonselectedTemplateKeepsSelectionAndUnsavedDraft() {
        val selected = BatchTemplate("selected", "선택", BatchClipMode.KEEP_FIRST, 1_000, 0, false)
        val other = selected.copy(id = "other", name = "다른 템플릿")
        TemplateStore(context).save(selected)
        TemplateStore(context).save(other)
        model = newModel()
        compose.runOnIdle {
            assertEquals(listOf(selected, other), model.templates)
            model.chooseTemplate(selected.id)
            assertEquals(selected.name, model.draftName)
            model.draftLeadingSeconds = "3"
            model.removeTemplate(context, other.id)
            assertEquals(selected.id, model.selectedTemplateId)
            assertEquals("3", model.draftLeadingSeconds)
            assertEquals(listOf(selected), TemplateStore(context).load())
        }
    }

    @After fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) {
            models.forEach { ViewModelStore().apply { put("batch", it) }.clear() }
        }
        models.flatMap { it.files }.mapNotNull { it.savedOutputUri }.distinct().forEach { output ->
            context.contentResolver.delete(Uri.parse(output), null, null)
        }
        context.cleanUp()
    }

    private fun newModel(): BatchViewModel = runBlocking {
        withContext(Dispatchers.Main) {
            BatchViewModel(application).also {
                models += it
                it.loadTemplates(application)
            }
        }
    }

    private fun openOptions() {
        compose.onNodeWithText("템플릿·저장 옵션 펼치기").performScrollTo().performClick()
    }

    private fun chooseOriginalAction(label: String) {
        compose.onNodeWithTag("batch-original-action").performScrollTo().performClick()
        compose.onNodeWithText(label).performClick()
    }

    private fun assertOriginalActionSelected(label: String) {
        compose.onNodeWithTag("batch-original-action")
            .assertContentDescriptionEquals("저장 후 원본 처리: $label")
        compose.onNodeWithTag("batch-original-action").performScrollTo().performClick()
        compose.onNodeWithText(label).assertIsSelected().performClick()
    }

    @Test fun cancellingEnableLeavesOriginalsKept() {
        openOptions()
        chooseOriginalAction("저장 성공한 원본만 휴지통으로 이동")
        compose.onNodeWithText("원본 자동 처리 활성화").assertExists()
        compose.runOnIdle { assertEquals(OriginalAction.KEEP, model.originalAction) }
        compose.onNodeWithText("취소").performClick()
        assertOriginalActionSelected("원본 유지 (기본값)")
        assertEquals(OriginalAction.KEEP, newModel().originalAction)
    }

    @Test fun confirmedPreferenceSurvivesNewBatchAndModelRecreation() {
        openOptions()
        chooseOriginalAction("저장 성공한 원본만 휴지통으로 이동")
        compose.onNodeWithText("활성화").performClick()
        assertOriginalActionSelected("저장 성공한 원본만 휴지통으로 이동")
        compose.runOnIdle {
            model.resetBatch()
            assertEquals(OriginalAction.TRASH, model.originalAction)
        }
        assertEquals(OriginalAction.TRASH, newModel().originalAction)
        // Selecting the already enabled preference must not repeat the app warning.
        chooseOriginalAction("저장 성공한 원본만 휴지통으로 이동")
        compose.onNodeWithText("원본 자동 처리 활성화").assertDoesNotExist()
    }

    @Test fun disablingIsImmediateAndReenablingOrChangingActionRequiresConfirmation() {
        openOptions()
        chooseOriginalAction("저장 성공한 원본만 휴지통으로 이동")
        compose.onNodeWithText("활성화").performClick()
        chooseOriginalAction("저장 성공한 원본 영구 삭제")
        compose.onNodeWithText("원본 자동 처리 활성화").assertExists()
        compose.runOnIdle { assertEquals(OriginalAction.TRASH, model.originalAction) }
        compose.onNodeWithText("취소").performClick()
        chooseOriginalAction("원본 유지 (기본값)")
        compose.onNodeWithText("원본 자동 처리 활성화").assertDoesNotExist()
        assertOriginalActionSelected("원본 유지 (기본값)")
        assertEquals(OriginalAction.KEEP, newModel().originalAction)
        chooseOriginalAction("저장 성공한 원본만 휴지통으로 이동")
        compose.onNodeWithText("원본 자동 처리 활성화").assertExists()
    }

    @Test fun saveAsNewAtBottomDoesNotOverwriteTheSelectedTemplate() {
        val saved = compose.runOnIdle {
            model.draftName = "첫 번째 설정"
            model.draftLeadingSeconds = "2"
            model.saveTemplate(application)
            model.templates.single()
        }
        compose.runOnIdle {
            model.draftName = "두 번째 설정"
            model.draftLeadingSeconds = "5"
        }
        // Acknowledge the first save without reselecting a template or losing the edited draft.
        compose.onNodeWithText("템플릿을 저장했습니다.").assertExists()
        compose.onNodeWithText("확인").performClick()
        compose.onNodeWithTag("batch-template-manager").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(saved.id, model.selectedTemplateId)
            assertEquals("두 번째 설정", model.draftName)
            assertEquals("5", model.draftLeadingSeconds)
        }
        compose.onNodeWithText("새 템플릿으로 저장").performScrollTo().performClick()
        val stored = TemplateStore(application).load()
        assertEquals(2, stored.size)
        assertEquals(saved, stored.first { it.id == saved.id })
        val copy = stored.first { it.name == "두 번째 설정" }
        assertNotEquals(saved.id, copy.id)
        assertEquals(5_000L, copy.leadingMs)
        compose.runOnIdle { assertEquals(copy.id, model.selectedTemplateId) }
    }

    @Test fun publishedOriginalRequestWaitsForResumeAndIsNotRepeated() = runBlocking {
        val source = File(context.filesDir, "original.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_keyframes.mp4")
            .use { input -> source.outputStream().use { input.copyTo(it) } }
        withContext(Dispatchers.Main) {
            model.draftMode = BatchClipMode.KEEP_FIRST
            model.draftLeadingSeconds = "2"
            model.originalAction = OriginalAction.TRASH
            // File-backed fixture has no guaranteed original-date metadata; this test covers
            // publication and lifecycle delivery, independently of the default date policy.
            model.saveDateMode = SaveDateMode.CURRENT
            model.addVideos(application, listOf(Uri.fromFile(source)))
        }
        withTimeout(30_000L) { while (model.isImporting) delay(25L) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        withContext(Dispatchers.Main) { model.start(application, exportMode = ExportMode.FAST_ONLY) }
        withTimeout(60_000L) { while (model.isRunning) delay(25L) }
        assertEquals("Expected a published output: " + model.files.joinToString {
            "${it.status}: ${it.reason}"
        } + "; notification=${model.notification}", 1, model.successes)
        assertEquals(0, approvalRequests)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000L) { approvalRequests == 1 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(1, approvalRequests)
        compose.runOnIdle { model.originalApprovalResult(false) }
        compose.waitForIdle()
        assertEquals(1, approvalRequests)
        compose.runOnIdle { model.promptOriginalApproval() }
        compose.waitUntil(10_000L) { approvalRequests == 2 }
    }

    /** Real preferences and journals, isolated from other instrumentation tests. */
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "batch_preferences_${UUID.randomUUID()}_"
        private val preferenceNames = mutableSetOf<String>()
        private val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            preferenceNames += isolatedName
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        fun cleanUp() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            directory.deleteRecursively()
        }
    }
}
