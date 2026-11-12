package com.hebi.quickcut.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hebi.quickcut.settings.QuickCutThemeMode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.segments.ExtractionSessionStore
import com.hebi.quickcut.segments.ExtractionScreen
import com.hebi.quickcut.segments.ExtractionViewModel
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class Stage14ExtractionScreensInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var video: File
    private lateinit var store: ExtractionSessionStore
    private lateinit var model: ExtractionViewModel

    @Before fun prepareFixture() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        video = File(app.cacheDir, "quickcut_stage14_fixture.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_keyframes.mp4").use { input ->
            video.outputStream().use { output -> input.copyTo(output) }
        }
        store = ExtractionSessionStore(app, "quickcut_stage14_test_only.json")
        store.clear()
        model = ExtractionViewModel(app, store)
        model.importVideo(app, Uri.fromFile(video))
    }

    @After fun cleanupFixture() {
        video.delete()
        store.clear()
    }

    @Test fun singleAndTripleSegmentScreensAreCaptured() {
        var theme by mutableStateOf(QuickCutThemeMode.LIGHT)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))) {
                QuickCutTheme(modeOverride = theme) {
                    Ui07aAppSafeSurface {
                        ExtractionScreen(model, onSelectVideo = {}, onBack = {})
                    }
                }
            }
        }
        waitForImport()
        compose.onNodeWithText("여러 구간 추출").assertIsDisplayed()
        capture("stage14_extraction_one.png")
        compose.runOnIdle {
            val duration = requireNotNull(model.session).durationMs
            model.addSegment(duration / 3)
            model.addSegment(duration * 2 / 3)
        }
        compose.onNodeWithText("추출할 구간 (3개)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ui04b-extraction-range-summary").performScrollTo().assertIsDisplayed()
        capture("stage14_extraction_three.png")
        capture("ui04b_extraction_three.png")
        Ui07aVisualEvidence.capture("ui07a_light_extraction.png")
        compose.runOnIdle { theme = QuickCutThemeMode.DARK }
        compose.onNodeWithText("추출할 구간 (3개)").performScrollTo().assertIsDisplayed()
        Ui07aVisualEvidence.capture("ui07a_dark_extraction.png")
    }

    @Test fun tabletAndLargeFontStillShowSegmentControls() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(860.dp, 640.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.6f)) {
                    QuickCutTheme {
                        Ui07aAppSafeSurface {
                            ExtractionScreen(model, onSelectVideo = {}, onBack = {})
                        }
                    }
                }
            }
        }
        waitForImport()
        compose.onNodeWithText("구간 적용").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ui04b-extraction-range-summary").performScrollTo().assertIsDisplayed()
        capture("stage14_extraction_tablet_largefont.png")
        capture("ui04b_extraction_largefont.png")
    }

    private fun waitForImport() {
        compose.waitUntil(15_000L) { model.session != null && !model.importing && !model.recovering }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        check(name in setOf(
            "stage14_extraction_one.png",
            "stage14_extraction_three.png",
            "stage14_extraction_tablet_largefont.png",
            "ui04b_extraction_three.png",
            "ui04b_extraction_largefont.png",
        ))
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "design-review")
        check(folder.isDirectory || folder.mkdirs())
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Android 화면 캡처에 실패했습니다.")
        val file = File(folder, name)
        try {
            file.outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
        check(file.length() > 0L)
    }
}
