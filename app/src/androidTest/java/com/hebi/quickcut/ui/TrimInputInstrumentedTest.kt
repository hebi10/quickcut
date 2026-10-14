package com.hebi.quickcut.ui

import android.app.Application
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.batch.BatchViewModel
import com.hebi.quickcut.editor.EditorSessionSnapshot
import com.hebi.quickcut.editor.EditorSessionStore
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.QuickCutPage
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.settings.QuickTrimSettings
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            composeRule.waitUntil(120_000L) { model.page != QuickCutPage.EXPORTING }
            model.completedUri?.let {
                InstrumentationRegistry.getInstrumentation().targetContext
                    .contentResolver.delete(it, null, null)
            }
            // Cancel the editor's delayed session write before restoring shared preferences.
            composeRule.runOnUiThread { model.discardEditing() }
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
        composeRule.waitUntil(25_000L) { model.needsReencodeConsent }
        composeRule.onNodeWithText("정밀 저장 (재인코딩 허용)").performClick()
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

    private fun showConstrainedQuick(width: Int, height: Int) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                QuickCutTheme {
                    Box(Modifier.requiredSize(width.dp, height.dp)) {
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
