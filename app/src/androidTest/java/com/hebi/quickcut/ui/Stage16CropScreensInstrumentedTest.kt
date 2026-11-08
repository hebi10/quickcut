package com.hebi.quickcut.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class Stage16CropScreensInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var source: File

    @Before fun createFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        source = File(context.cacheDir, "stage16_screen_fixture.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_keyframes.mp4")
            .use { input -> source.outputStream().use { output -> input.copyTo(output) } }
    }

    @After fun clearFixture() {
        source.delete()
    }

    private fun clip(): VideoClip = VideoClip(1, Uri.fromFile(source).toString(), 8_000)

    @Test fun verticalSquareAndFreeformHaveRealVideoCapture() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))) {
                QuickCutTheme {
                    CropEditorScreen(clip(), onApply = {}, onCancel = {})
                }
            }
        }
        waitPreview()
        compose.onNodeWithTag("crop-preset-VERTICAL").performClick()
        capture("stage16_crop_vertical.png")
        compose.onNodeWithTag("crop-preset-SQUARE").performClick()
        capture("stage16_crop_square.png")
        compose.onNodeWithTag("crop-preset-FREE").performClick()
        compose.onNodeWithTag("crop-free-width").performScrollTo().assertIsDisplayed()
        capture("stage16_crop_free.png")
    }

    @Test fun largeFontTabletCanReachApplyAction() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(860.dp, 640.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.6f)) {
                    QuickCutTheme {
                        CropEditorScreen(clip(), onApply = {}, onCancel = {})
                    }
                }
            }
        }
        waitPreview()
        compose.onNodeWithTag("crop-apply").performScrollTo().assertIsDisplayed()
        capture("stage16_crop_largefont.png")
    }

    private fun waitPreview() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("crop-preview").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        require(name in setOf("stage16_crop_vertical.png", "stage16_crop_square.png",
            "stage16_crop_free.png", "stage16_crop_largefont.png"))
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "design-review")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Android 화면 캡처 실패")
        val output = File(directory, name)
        try {
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        check(output.length() > 0L)
    }
}
