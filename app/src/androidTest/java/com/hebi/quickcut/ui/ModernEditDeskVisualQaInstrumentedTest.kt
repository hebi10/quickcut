package com.hebi.quickcut.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.TextLayer
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickCutThemeMode
import com.hebi.quickcut.settings.QuickTrimSettings
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ModernEditDeskVisualQaInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var fixture: File

    @Before fun prepare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fixture = File(instrumentation.targetContext.cacheDir, "modern_editdesk_qa.mp4")
        instrumentation.context.assets.open("quickcut_fixture.mp4").use { input ->
            fixture.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @After fun cleanUp() { if (::fixture.isInitialized) fixture.delete() }

    private fun clip(): VideoClip = VideoClip(
        id = 1L, uri = Uri.fromFile(fixture).toString(),
        sourceDurationMs = 3_000L, hasAudio = true,
    )

    @Test fun darkSmallScreenLargeFontKeepsHomeAndNavigationActionsReachable() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.6f)) {
                    QuickCutTheme(modeOverride = QuickCutThemeMode.DARK) {
                        StudioStartScreen(
                            onQuick = {}, onSelect = {}, onBatch = {},
                            canRestore = false, onRestore = {},
                            quickSettings = QuickTrimSettings(),
                            dateMode = SaveDateMode.CURRENT,
                            onPreferences = {}, onLicenses = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("+ 영상 편집 시작하기").assertIsDisplayed()
        capture("modern_dark_home_320_font160.png")
        compose.onNodeWithText("프로젝트").performClick()
        compose.onNodeWithText("영상 편집 시작하기").performScrollTo().assertIsDisplayed()
        capture("modern_dark_projects_320_font160.png")
        compose.onNodeWithText("도구").performClick()
        compose.onNodeWithText("영상 갤러리").performScrollTo().assertIsDisplayed()
        capture("modern_dark_tools_320_font160.png")
        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("화면 테마").performScrollTo().assertIsDisplayed()
        capture("modern_dark_settings_320_font160.png")
    }

    @Test fun darkCropDisplaysAndActivatesApply() {
        var applied = false
        compose.setContent {
            QuickCutTheme(modeOverride = QuickCutThemeMode.DARK) {
                Box(Modifier.fillMaxSize().background(LocalCutPalette.current.background)) {
                    CropEditorScreen(clip(), onApply = { applied = true }, onCancel = {})
                }
            }
        }
        compose.waitUntil(15_000L) {
            compose.onAllNodesWithTag("crop-preview").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("crop-apply").assertIsDisplayed().assertIsEnabled()
        capture("modern_dark_crop.png")
        compose.onNodeWithTag("crop-apply").performClick()
        compose.runOnIdle { check(applied) { "Dark crop Apply action was not triggered" } }
    }

    @Test fun darkAudioDisplaysAndActivatesApply() {
        var applied = false
        compose.setContent {
            QuickCutTheme(modeOverride = QuickCutThemeMode.DARK) {
                AudioEditorScreen(
                    clip = clip(),
                    onApply = { _, _ -> applied = true },
                    onCancel = {},
                )
            }
        }
        compose.onNodeWithTag("audio-apply").assertIsDisplayed().assertIsEnabled()
        capture("modern_dark_audio.png")
        compose.onNodeWithTag("audio-apply").performClick()
        compose.runOnIdle { check(applied) { "Dark audio Apply action was not triggered" } }
    }

    @Test fun darkTextDisplaysAndActivatesApply() {
        var applied = false
        val withText = clip().copy(
            textLayers = listOf(TextLayer(1L, "QuickCut 다크 테스트", 0L, 2_800L)),
        )
        compose.setContent {
            QuickCutTheme(modeOverride = QuickCutThemeMode.DARK) {
                TextEditorScreen(
                    clip = withText,
                    onApply = { applied = true },
                    onCancel = {},
                )
            }
        }
        compose.onNodeWithTag("text-apply").assertIsDisplayed().assertIsEnabled()
        capture("modern_dark_text.png")
        compose.onNodeWithTag("text-apply").performClick()
        compose.runOnIdle { check(applied) { "Dark text Apply action was not triggered" } }
    }

    @Test fun darkExportDialogPreservesSaveAndSourceOptions() {
        compose.setContent {
            QuickCutTheme(modeOverride = QuickCutThemeMode.DARK) {
                SaveOptionsDialog(
                    originalAction = OriginalAction.KEEP,
                    onOriginalActionChange = {},
                    dateMode = SaveDateMode.CURRENT,
                    onDateModeChange = {},
                    canSave = true, onSave = {}, onDismiss = {},
                    timeline = EditTimeline(listOf(clip())),
                )
            }
        }
        compose.onNodeWithText("저장 시작").assertIsDisplayed()
        compose.onNodeWithText("원본 동영상").performScrollTo().assertIsDisplayed()
        capture("modern_dark_export_dialog.png")
    }

    private fun capture(name: String) {
        require(name in setOf(
            "modern_dark_home_320_font160.png",
            "modern_dark_projects_320_font160.png",
            "modern_dark_tools_320_font160.png",
            "modern_dark_settings_320_font160.png",
            "modern_dark_crop.png",
            "modern_dark_audio.png",
            "modern_dark_text.png",
            "modern_dark_export_dialog.png",
        ))
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val folder = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "design-review",
        )
        check(folder.isDirectory || folder.mkdirs())
        val output = File(folder, name)
        try {
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        check(output.length() > 1000L)
    }
}
