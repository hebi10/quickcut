package com.hebi.quickcut.batch

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.BatchDraftSettings
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.ui.QuickCutTheme
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExactBatchExportInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var model: BatchViewModel
    private lateinit var source: File
    private lateinit var settings: QuickCutSettings
    private lateinit var previousDraft: BatchDraftSettings
    private lateinit var previousDate: SaveDateMode
    private lateinit var sessionStore: BatchSessionStore
    private var previousSession: BatchSessionSnapshot? = null

    @Before
    fun importThreeSecondCutBetweenKeyframes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
            assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        }
        settings = QuickCutSettings(context)
        previousDraft = settings.loadBatch()
        previousDate = settings.loadSaveDate()
        sessionStore = BatchSessionStore(context)
        previousSession = sessionStore.load()
        sessionStore.clear()
        source = File(context.cacheDir, "quickcut_batch_exact_3s.mp4")
        instrumentation.context.assets.open("quickcut_keyframes.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        composeRule.runOnUiThread {
            model = BatchViewModel(context.applicationContext as Application)
            model.draftName = "앞 3초 정확히 삭제"
            model.draftMode = BatchClipMode.REMOVE_EDGES
            model.draftLeadingSeconds = "3"
            model.draftTrailingSeconds = "0"
            model.draftMute = false
            model.saveDateMode = SaveDateMode.CURRENT
            model.addVideos(context, listOf(Uri.fromFile(source)))
        }
        composeRule.waitUntil(30_000L) { !model.isImporting && model.files.size == 1 }
        assertTrue("The fixture must retain more than the next keyframe interval",
            model.files.single().durationMs >= 7_900L)
        composeRule.setContent {
            QuickCutTheme { BatchScreen(model, {}, {}, { _, _, _ -> }) }
        }
    }

    @After
    fun cleanUp() {
        if (::model.isInitialized) {
            composeRule.runOnUiThread { model.cancel() }
            composeRule.waitUntil(120_000L) { !model.isRunning }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            model.files.mapNotNull(BatchFile::savedOutputUri).forEach { output ->
                runCatching { context.contentResolver.delete(Uri.parse(output), null, null) }
            }
            composeRule.runOnUiThread {
                ViewModelStore().apply { put("batch-exact", model) }.clear()
            }
        }
        if (::sessionStore.isInitialized) {
            previousSession?.let(sessionStore::save) ?: sessionStore.clear()
        }
        if (::settings.isInitialized) {
            settings.saveBatch(previousDraft)
            settings.saveSaveDate(previousDate)
        }
        if (::source.isInitialized) source.delete()
    }

    @Test
    fun exactAutomaticSaveKeepsThreeSecondCutAndReportsCompletedMethod() {
        composeRule.onNodeWithText("일괄 편집 및 저장 시작").performClick()
        composeRule.waitUntil(30_000L) {
            composeRule.onAllNodes(hasText("저장 방식 선택")).fetchSemanticsNodes().isNotEmpty()
        }
        // This source has sync frames at 2s and 4s. Exact automatic save must
        // preserve the requested 3s boundary instead of choosing the 4s shortcut.
        composeRule.onNodeWithText("정확한 위치로 자동 저장").assertIsEnabled().performClick()
        composeRule.waitUntil(120_000L) { model.runState == BatchRunState.FINISHED }

        val row = model.files.single()
        assertEquals("A valid non-keyframe cut must be saved", BatchFileStatus.SAVED, row.status)
        val requestedDuration = row.durationMs - 3_000L
        assertEquals(requestedDuration, row.outputExpectedDurationMs)
        assertEquals("3", model.draftLeadingSeconds)
        assertEquals(3_000L, model.previewTemplate()!!.leadingMs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actual = MediaReader.metadata(context, Uri.parse(row.savedOutputUri!!))
        assertTrue("Output must preserve the 3s cut, not move to the 4s keyframe",
            abs(actual.durationMs - requestedDuration) <= 250L)
        assertTrue("The source audio must remain present", actual.hasAudio)
        assertEquals(requestedDuration,
            sessionStore.load()!!.files.single().outputExpectedDurationMs)
        composeRule.onNodeWithText(formatTime(row.durationMs) + " → " +
            formatTime(requestedDuration)).performScrollTo().assertExists()
        composeRule.onNodeWithText("처리 방식:", substring = true)
            .performScrollTo().assertExists()
    }
}
