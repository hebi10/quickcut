package com.hebi.quickcut.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
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
                    Box(Modifier.fillMaxSize().background(CutColors.background).safeDrawingPadding()) {
                            TextEditorScreen(clip, onApply = {}, onCancel = {})
                        }
                }
            }
        }
        compose.onNodeWithTag("text-layer-add").performScrollTo().assertIsEnabled()
        capture("stage18_text_empty.png")
        // Replay a physical tap after revealing the button; semantics-only click can be
        // acknowledged even when the emulator has not delivered the touch to the UI.
        compose.onNodeWithTag("text-layer-add").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("text-layer-add").performTouchInput { click() }
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag("text-layer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("text-layer-input").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("text-invalid").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("text-apply").assertIsNotEnabled()
        compose.onNodeWithTag("text-layer-input").performTextInput("테스트 영상 문구")
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
                        Box(Modifier.fillMaxSize().background(CutColors.background).safeDrawingPadding()) {
                            TextEditorScreen(clip, onApply = {}, onCancel = {})
                        }
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
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var verified: Bitmap? = null
        // SwiftShader sometimes returns a transient white frame while the Compose surface
        // is being updated. Retry a bounded number of real captures; never accept a blank PNG.
        for (attempt in 1..6) {
            compose.waitForIdle()
            val candidate = automation.takeScreenshot()
            if (candidate != null) {
                val step = maxOf(1, candidate.width / 40)
                var scanned = 0
                var dark = 0
                for (y in candidate.height / 12 until candidate.height * 3 / 4 step step) {
                    for (x in 0 until candidate.width step step) {
                        val pixel = candidate.getPixel(x, y)
                        if (Color.red(pixel) < 96 && Color.green(pixel) < 96 &&
                            Color.blue(pixel) < 110
                        ) dark++
                        scanned++
                    }
                }
                if (dark * 25 > scanned) {
                    verified = candidate
                    break
                }
                candidate.recycle()
            }
            if (attempt < 6) Thread.sleep(300L)
        }
        val bitmap = checkNotNull(verified) {
            "Text editor screenshot is blank or hidden after 6 Android captures: $name"
        }
        val target = File(dir, name)
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        check(target.length() > 0L)
    }
}
