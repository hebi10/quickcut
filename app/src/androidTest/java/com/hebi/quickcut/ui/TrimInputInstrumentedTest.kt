package com.hebi.quickcut.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithContentDescription
import android.app.Application
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.batch.BatchViewModel
import com.hebi.quickcut.editor.EditorSessionSnapshot
import com.hebi.quickcut.editor.EditorSessionStore
import androidx.lifecycle.ViewModelStore
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.QuickCutPage
import com.hebi.quickcut.export.ExportWorkMonitor
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.settings.QuickTrimSettings
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regressions use the real fields/player/exporter; the fixture is created by emulator CI. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class TrimInputInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var model: EditorViewModel
    private lateinit var batchModel: BatchViewModel
    private lateinit var settings: QuickCutSettings
    private lateinit var source: File
    private lateinit var store: EditorSessionStore
    private var previousSession: EditorSessionSnapshot? = null
    private lateinit var previousQuick: QuickTrimSettings
    private lateinit var previousDate: SaveDateMode

    @Before
    fun importVideo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        settings = QuickCutSettings(context)
        previousQuick = settings.loadQuick()
        previousDate = settings.loadSaveDate()
        settings.saveSaveDate(SaveDateMode.CURRENT)
        store = EditorSessionStore(context)
        previousSession = store.load()
        source = File(context.cacheDir, "quickcut_ui_trim_fixture.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("quickcut_fixture.mp4").use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
        composeRule.runOnUiThread {
            val application = context.applicationContext as Application
            model = EditorViewModel(application)
            batchModel = BatchViewModel(application)
            model.changeQuickMode(true)
            model.importVideos(context, listOf(Uri.fromFile(source)))
        }
        composeRule.waitUntil(25_000L) { model.page == QuickCutPage.EDITOR }
    }

    @After
    fun cleanUp() {
        if (::model.isInitialized) {
            composeRule.runOnUiThread { model.cancelExport() }
            composeRule.waitUntil(120_000L) { !ExportWorkMonitor.isRunning && !model.isRecoveringExport }
            model.completedUri?.let {
                InstrumentationRegistry.getInstrumentation().targetContext
                    .contentResolver.delete(it, null, null)
            }
            // Cancel the editor's delayed session write before restoring shared preferences.
            composeRule.runOnUiThread {
                model.discardEditing()
                ViewModelStore().apply { put("editor", model); put("batch", batchModel) }.clear()
            }
        }
        if (::store.isInitialized) {
            previousSession?.let(store::save) ?: store.clear()
        }
        if (::settings.isInitialized) {
            settings.saveQuick(previousQuick)
            settings.saveSaveDate(previousDate)
        }
        if (::source.isInitialized) source.delete()
    }

    private fun showQuick(onSave: () -> Unit = {}) {
        composeRule.setContent {
            QuickCutTheme {
                QuickTrimScreen(model, settings, {}, {}, onSave, {})
            }
        }
    }

    private fun showApp() {
        composeRule.setContent {
            QuickCutTheme { QuickCutApp(model, batchModel) }
        }
    }

    @Test fun savingUnchangedRoundedFieldsPreservesPreviouslyAcceptedPreciseTimes() {
        composeRule.runOnUiThread {
            val suggestion = com.hebi.quickcut.media.FastCutSuggestion.createUs(model.timeline,
                listOf(1L to 2_999_999L))!!
            assertTrue(model.applyFastCutSuggestion(suggestion))
        }
        var saved = false
        showQuick { saved = true }
        composeRule.onNodeWithText("영상 저장").performClick()
        composeRule.runOnIdle {
            assertTrue(saved)
            assertEquals(1L, model.timeline.clips.single().startUs)
            assertEquals(2_999_999L, model.timeline.clips.single().endUs)
        }
    }

    @Test fun splitIsDisabledWhenPreciseRemainderIsUnderMinimumDespiteRoundedDisplay() {
        composeRule.runOnUiThread {
            assertTrue(model.applyFastCutSuggestion(com.hebi.quickcut.media.FastCutSuggestion.createUs(
                model.timeline, listOf(0L to 333_333L))!!))
            model.seek(234L)
            org.junit.Assert.assertFalse(model.canSplitSelected)
            val original = model.timeline
            model.splitSelected()
            assertEquals(original, model.timeline)
        }
    }

    @Test
    fun acceptedRecommendationUsesRealEditorAndExporterWithoutChangingQuickDefaults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.runOnUiThread { model.discardEditing() }
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_bframes.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        composeRule.runOnUiThread {
            model.changeQuickMode(true)
            model.importVideos(context, listOf(Uri.fromFile(source)))
        }
        composeRule.waitUntil(25_000L) { model.page == QuickCutPage.EDITOR }
        composeRule.runOnUiThread { model.trimSelected(3_000L, 7_500L) }
        showApp()
        composeRule.onNodeWithText("영상 저장").performClick()
        composeRule.waitUntil(60_000L) {
            composeRule.onAllNodes(hasText("추천 위치로 고속 저장")).fetchSemanticsNodes().isNotEmpty()
        }
        val defaults = settings.loadQuick()
        composeRule.runOnIdle {
            assertEquals(3_000L, model.timeline.clips.single().startMs)
            assertEquals(7_500L, model.timeline.clips.single().endMs)
        }
        composeRule.onNodeWithText("추천 위치로 고속 저장").performClick()
        composeRule.waitUntil(120_000L) { model.page == QuickCutPage.COMPLETED }
        composeRule.runOnIdle {
            assertEquals(4_000L, model.timeline.clips.single().startMs)
            assertEquals(6_000L, model.timeline.clips.single().endMs)
            assertEquals(ExportMode.FAST_ONLY, model.requestedExportMode)
            assertEquals(com.hebi.quickcut.media.ExportMethod.AUDIO_REENCODE, model.completedExportReport!!.method)
            assertEquals(defaults, settings.loadQuick())
        }
        com.hebi.quickcut.media.MediaReader.validateExport(context, model.completedUri!!, 2_000L, true)
    }

    @Test
    fun deletingLastClipKeepsUndoAndRedoAccessible() {
        composeRule.runOnUiThread { model.changeQuickMode(false) }
        showApp()
        val original = model.timeline
        composeRule.runOnUiThread {
            model.deleteSelected()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            model.importVideos(context,
                listOf(Uri.fromFile(File(context.cacheDir, "missing_added_video.mp4"))), append = true)
        }
        composeRule.waitUntil(25_000L) { model.errorMessage != null }
        composeRule.onNodeWithText("확인", substring = false).performClick()
        composeRule.onNodeWithContentDescription("실행 취소").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(original, model.timeline) }
        composeRule.onNodeWithContentDescription("다시 실행").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertTrue(model.timeline.isEmpty) }
        composeRule.onNodeWithContentDescription("실행 취소").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(original, model.timeline) }
    }

    @Test
    fun addingAfterLastDeletionKeepsBothUndoSteps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.runOnUiThread { model.changeQuickMode(false) }
        showApp()
        val original = model.timeline
        composeRule.runOnUiThread {
            model.deleteSelected()
            model.importVideos(context, listOf(Uri.fromFile(source)), append = true)
        }
        composeRule.waitUntil(25_000L) { model.page == QuickCutPage.EDITOR && !model.timeline.isEmpty }
        composeRule.onNodeWithContentDescription("실행 취소").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertTrue(model.timeline.isEmpty) }
        composeRule.onNodeWithContentDescription("실행 취소").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(original, model.timeline) }
        composeRule.onNodeWithContentDescription("다시 실행").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertTrue(model.timeline.isEmpty) }
        composeRule.onNodeWithContentDescription("다시 실행").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, model.timeline.clips.size) }
    }

    @Test
    fun quickPreviewPositionSurvivesDisposalAndDetailedEditing() {
        val visible = mutableStateOf(true)
        composeRule.setContent {
            QuickCutTheme {
                if (visible.value) {
                    if (model.quickMode) QuickTrimScreen(model, settings, {}, {}, {}, {})
                    else EditorScreen(model, {}, {})
                }
            }
        }
        composeRule.waitUntil(15_000L) {
            composeRule.runOnIdle {
                findPlayerView(composeRule.activity.window.decorView)?.player?.playbackState ==
                    androidx.media3.common.Player.STATE_READY
            }
        }
        composeRule.runOnUiThread { requirePlayerView().player!!.seekTo(1_500L) }
        composeRule.waitUntil(5_000L) {
            composeRule.runOnIdle { requirePlayerView().player!!.currentPosition >= 1_400L }
        }
        composeRule.runOnUiThread { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue(model.playPositionMs >= 1_400L) }
        composeRule.runOnUiThread { visible.value = true }
        composeRule.waitUntil(15_000L) {
            composeRule.runOnIdle {
                findPlayerView(composeRule.activity.window.decorView)?.player?.currentPosition?.let { it >= 1_400L } == true
            }
        }
        composeRule.runOnUiThread { model.changeQuickMode(false) }
        composeRule.waitUntil(15_000L) {
            composeRule.runOnIdle {
                findPlayerView(composeRule.activity.window.decorView)?.player?.currentPosition?.let { it >= 1_400L } == true
            }
        }
        composeRule.runOnIdle { assertTrue(model.playPositionMs >= 1_400L) }
    }

    @Test
    fun failedQuickPickerDoesNotApplyDefaultsToDetailedImport() = checkFailedQuickPicker(false)

    @Test
    fun failedQuickPickerDoesNotApplyDefaultsToRestoredSession() = checkFailedQuickPicker(true)

    private fun checkFailedQuickPicker(restore: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = model.timeline.clips.single()
        val snapshot = EditorSessionSnapshot(model.timeline, original.id, 0L, false)
        settings.saveQuick(QuickTrimSettings(500L, 500L, true))
        composeRule.runOnUiThread { model.discardEditing() }
        if (restore) store.save(snapshot)
        // The registry delivers real OpenDocument contract results without launching a system picker.
        var nextUri = Uri.fromFile(File(context.cacheDir, "missing_quick_video.mp4"))
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(nextUri))
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                QuickCutTheme { QuickCutApp(model, batchModel) }
            }
        }
        composeRule.onNodeWithText("빠른 자르기 · 영상 1개").performClick()
        composeRule.waitUntil(25_000L) { model.errorMessage != null }
        composeRule.onNodeWithText("확인", substring = false).performClick()
        if (restore) {
            // The failed import may schedule a session write, so restore the saved input explicitly.
            store.save(snapshot)
            composeRule.runOnUiThread { model.restoreSavedSession(context) }
        } else {
            nextUri = Uri.fromFile(source)
            composeRule.onNodeWithText("상세 편집 (분할·연결·순서 변경)").performClick()
        }
        composeRule.waitUntil(25_000L) { model.page == QuickCutPage.EDITOR }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val clip = model.timeline.clips.single()
            assertEquals(0L, clip.startMs)
            assertEquals(clip.sourceDurationMs, clip.endMs)
            assertEquals(false, clip.muted)
        }
    }

    @Test
    fun exactlyOneHundredMillisecondsUpdatesTheRangeThatWillBeSaved() {
        var savedDuration = -1L
        showQuick { savedDuration = model.timeline.totalDurationMs }
        val duration = model.timeline.clips.single().sourceDurationMs
        composeRule.onAllNodes(hasSetTextAction())[0]
            .performTextReplacement(((duration - 100L) / 1_000.0).toString())
        composeRule.waitForIdle()

        assertEquals("The valid 0.1-second input must replace the previous range",
            100L, model.timeline.totalDurationMs)
        composeRule.onNodeWithText("영상 저장").assertIsEnabled().performClick()
        assertEquals(100L, savedDuration)
        assertEquals(duration - 100L, settings.loadQuick().leadingMs)
    }

    @Test
    fun invalidQuickInputDisablesBothSaveEntryPoints() {
        showQuick()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("-")

        composeRule.onNodeWithText("영상 저장").assertIsNotEnabled()
        composeRule.onNodeWithText("저장 옵션 · 날짜 및 원본 처리")
            .performScrollTo().assertIsNotEnabled()
        assertEquals("Invalid input must leave the last valid timeline untouched",
            model.timeline.clips.single().sourceDurationMs, model.timeline.totalDurationMs)
    }

    @Test
    fun invalidDetailedTimeDisablesTheHeaderSaveEntryPoint() {
        composeRule.runOnUiThread { model.changeQuickMode(false) }
        showApp()
        composeRule.onNodeWithText("자르기").performClick()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("-")

        composeRule.onNodeWithText("저장", substring = false).assertIsNotEnabled()
    }

    @Test
    fun reselectingTheSameClipDoesNotClearAnInvalidTimeDraft() {
        composeRule.runOnUiThread { model.changeQuickMode(false) }
        showApp()
        composeRule.onNodeWithText("자르기").performClick()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("-")
        composeRule.onNode(hasClickAction() and hasText(formatTime(model.timeline.totalDurationMs))).performClick()
        composeRule.onNodeWithText("저장", substring = false).assertIsNotEnabled()
    }

    @kotlin.OptIn(ExperimentalTestApi::class)
    @Test
    fun exportCancelRemainsAccessibleInALowWindowWithLargeFont() {
        var cancelled = false
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(640.dp, 320.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    QuickCutTheme { CenterStatus("영상 저장 중", "다른 앱으로 이동해도 저장됩니다.", 50) { cancelled = true } }
                }
            }
        }
        composeRule.onNodeWithText("취소", substring = false).performScrollTo().assertIsDisplayed().performClick()
        assertTrue(cancelled)
    }

    @Test
    fun detailedRangeSliderCanCorrectAnInvalidRawTime() {
        composeRule.runOnUiThread { model.changeQuickMode(false) }
        showApp()
        composeRule.onNodeWithText("자르기").performClick()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("-")
        composeRule.onNodeWithText("저장", substring = false).assertIsNotEnabled()

        composeRule.onNodeWithTag("detailed-trim-range").performTouchInput {
            click(center)
        }
        composeRule.onNodeWithText("저장", substring = false).assertIsEnabled()
        composeRule.onAllNodes(hasSetTextAction())[0].assertIsDisplayed()
    }

    @Test
    fun switchingToDetailedEditingDiscardsTheInvalidQuickDraft() {
        showApp()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("-")
        composeRule.onNodeWithText("상세 편집", substring = false).performClick()

        composeRule.onNodeWithText("저장", substring = false).assertIsEnabled()
    }

    @Test
    fun previewVolumeFollowsTheQuickMuteChoice() {
        showQuick()
        composeRule.runOnIdle {
            assertEquals(1f, requirePlayerView().player!!.volume, 0f)
        }
        composeRule.onNodeWithText("음소거", substring = false)
            .performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(model.timeline.clips.single().muted)
            assertEquals("The preview must be silent when the export is muted",
                0f, requirePlayerView().player!!.volume, 0f)
        }
        composeRule.onNodeWithText("소리 켜기", substring = false)
            .performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1f, requirePlayerView().player!!.volume, 0f)
        }
    }

    @Test
    fun unreadableQuickPreviewReportsAnError() {
        assertTrue(source.delete())
        showQuick()

        composeRule.waitUntil(15_000L) { model.errorMessage != null }
        assertNotNull("A player error must produce user feedback", model.errorMessage)
        assertTrue(model.errorMessage!!.contains("미리보기"))
    }

    @Test
    fun reencodeRetryKeepsTheFailedExportsOriginalAndDateChoices() {
        showApp()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.runOnUiThread {
            model.trimSelected(500L, 2_500L)
            // This real FAST_ONLY export requires reencoding. It also reproduces returning
            // to the UI after activity recreation, with the options held by the model.
            model.startExport(context, OriginalAction.TRASH, SaveDateMode.ORIGINAL,
                ExportMode.FAST_ONLY)
        }
        composeRule.waitUntil(25_000L) {
            model.needsReencodeConsent && !ExportWorkMonitor.isRunning && !model.isRecoveringExport
        }
        composeRule.runOnIdle { assertNull(model.exportRecoveryNotice) }
        composeRule.onNodeWithText("이전 저장 확인").assertDoesNotExist()
        composeRule.waitUntil(25_000L) {
            composeRule.onAllNodes(hasText("정확한 위치로 자동 저장"))
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("정확한 위치로 자동 저장")
            .assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals("Retry must preserve the original handling approval choice",
                OriginalAction.TRASH, model.originalAction)
            assertEquals("Retry must preserve the original timestamp choice",
                SaveDateMode.ORIGINAL, model.savedDateMode)
            assertEquals(ExportMode.ALLOW_REENCODE, model.requestedExportMode)
        }
    }

    @Test
    fun narrowQuickTrimWithLargeFontKeepsSaveOptionsReachable() {
        showConstrainedQuick(width = 320, height = 640)

        composeRule.onNodeWithText("영상 저장").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("저장 옵션 · 날짜 및 원본 처리")
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeRule.onAllNodes(hasSetTextAction())[0].performScrollTo().assertIsDisplayed()
    }

    @Test
    fun landscapeQuickTrimWithLargeFontKeepsSaveOptionsReachable() {
        showConstrainedQuick(width = 640, height = 320)

        composeRule.onNodeWithText("영상 저장").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("저장 옵션 · 날짜 및 원본 처리")
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeRule.onAllNodes(hasSetTextAction())[0].performScrollTo().assertIsDisplayed()
    }

    @kotlin.OptIn(ExperimentalTestApi::class)
    private fun showConstrainedQuick(width: Int, height: Int) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    QuickCutTheme {
                        QuickTrimScreen(model, settings, {}, {}, {}, {})
                    }
                }
            }
        }
    }

    private fun requirePlayerView(): PlayerView =
        findPlayerView(composeRule.activity.window.decorView)
            ?: throw AssertionError("The quick preview PlayerView was not attached")

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findPlayerView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}

