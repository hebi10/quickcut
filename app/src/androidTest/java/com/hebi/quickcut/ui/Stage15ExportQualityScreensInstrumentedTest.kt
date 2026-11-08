package com.hebi.quickcut.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.ExportQualitySettings
import com.hebi.quickcut.media.ExportResolution
import com.hebi.quickcut.media.ExportQualityLevel
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.SaveDateMode
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class Stage15ExportQualityScreensInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var source: File

    @Before fun prepareVideo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        source = File(context.cacheDir, "stage15_quality_fixture.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_keyframes.mp4")
            .use { input -> source.outputStream().use { output -> input.copyTo(output) } }
    }

    @After fun cleanUp() {
        source.delete()
    }

    @Test fun actualSaveSettingsAllowAutomaticAndExplicitQualitySelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.fromFile(source)
        val metadata = MediaReader.metadata(context, uri)
        val timeline = EditTimeline(listOf(VideoClip(
            id = 1L, uri = uri.toString(), sourceDurationMs = metadata.durationMs,
            hasAudio = metadata.hasAudio,
        )))
        var selected by mutableStateOf(ExportQualitySettings())
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))) {
                QuickCutTheme {
                    SaveOptionsDialog(
                        originalAction = OriginalAction.KEEP,
                        onOriginalActionChange = {},
                        dateMode = SaveDateMode.CURRENT,
                        onDateModeChange = {},
                        canSave = true, onSave = {}, onDismiss = {},
                        timeline = timeline, outputQuality = selected,
                        onOutputQualityChange = { selected = it },
                    )
                }
            }
        }
        compose.onNodeWithTag("export-quality-resolution").performScrollTo().assertExists()
        capture("stage15_export_auto.png")
        compose.onNodeWithTag("export-quality-bitrate").performScrollTo().performClick()
        compose.onNodeWithTag("cut-choice-option-보통").performClick()
        compose.waitUntil(15_000) {
            selected.quality == ExportQualityLevel.BALANCED &&
                (compose.onAllNodesWithTag("export-quality-estimate").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag("export-quality-unavailable").fetchSemanticsNodes().isNotEmpty())
        }
        compose.onNodeWithTag("export-quality-bitrate").assertContentDescriptionEquals("압축 품질: 보통")
        capture("stage15_export_quality.png")
        compose.onNodeWithTag("export-quality-resolution").performScrollTo().performClick()
        compose.onNodeWithTag("cut-choice-option-4K").performClick()
        compose.waitUntil(15_000) {
            selected.resolution == ExportResolution.ULTRA_HD_2160 &&
                compose.onAllNodesWithTag("export-quality-unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("export-quality-resolution").assertContentDescriptionEquals("해상도: 4K")
        compose.onNodeWithTag("export-quality-unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("저장 시작").assertIsNotEnabled()
        capture("stage15_export_unsupported.png")
    }

    private fun capture(name: String) {
        check(name in setOf("stage15_export_auto.png", "stage15_export_quality.png",
            "stage15_export_unsupported.png"))
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "design-review")
        check(dir.isDirectory || dir.mkdirs())
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Android 실제 화면 캡처에 실패했습니다.")
        val output = File(dir, name)
        try {
            output.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            image.recycle()
        }
        check(output.length() > 0L)
    }
}
