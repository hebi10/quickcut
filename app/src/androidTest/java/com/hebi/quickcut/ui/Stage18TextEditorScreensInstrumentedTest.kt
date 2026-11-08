package com.hebi.quickcut.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.TextLayer
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class Stage18TextEditorScreensInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var source: File

    @Before fun prepare() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        source = File(instrument.targetContext.cacheDir, "stage18_text_ui.mp4")
        instrument.context.assets.open("quickcut_fixture.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @After fun cleanup() { source.delete() }

    @Test fun textEditEmptyEntrySelectedColorAndTimingCapture() {
        val clip = VideoClip(1, Uri.fromFile(source).toString(), 3_000)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))) {
                QuickCutTheme {
                    TextEditorScreen(clip, onApply = {}, onCancel = {})
                }
            }
        }
        compose.onNodeWithTag("text-layer-add").performScrollTo().assertIsEnabled()
        capture("stage18_text_empty.png")
        compose.onNodeWithTag("text-layer-add").performClick()
        compose.onNodeWithTag("text-apply").assertIsNotEnabled()
        compose.onNodeWithTag("text-layer-input").performScrollTo()
            .performTextInput("테스트 영상 문구")
        compose.onNodeWithTag("text-apply").assertIsEnabled()
        capture("stage18_text_selected.png")
        compose.onNodeWithTag("text-color-YELLOW").performScrollTo().performClick()
        compose.onNodeWithTag("text-layer-timing").performScrollTo().assertIsDisplayed()
        capture("stage18_text_styled.png")
    }

    @Test fun largeFontTabletRendersPinnedApplyAndMultipleLayers() {
        val clip = VideoClip(1, Uri.fromFile(source).toString(), 3_000, textLayers = listOf(
            TextLayer(1L, "QuickCut Test", 0L, 2_800L),
            TextLayer(2L, "두 번째 문구", 200L, 2_800L),
        ))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(860.dp, 640.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.6f)) {
                    QuickCutTheme {
                        TextEditorScreen(clip, onApply = {}, onCancel = {})
                    }
                }
            }
        }
        compose.onNodeWithTag("text-apply").assertIsDisplayed()
        compose.onNodeWithTag("text-apply").assertIsEnabled()
        capture("stage18_text_largefont.png")
    }

    private fun capture(name: String) {
        require(name in setOf(
            "stage18_text_empty.png", "stage18_text_selected.png",
            "stage18_text_styled.png", "stage18_text_largefont.png",
        ))
        compose.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "design-review")
        check(dir.isDirectory || dir.mkdirs())
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Android 화면 캡처 실패")
        val target = File(dir, name)
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        check(target.length() > 0L)
    }
}
