package com.hebi.quickcut.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
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
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
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
        // Gesture tests must use the emulator's real visible window. ForcedSize is
        // reserved for the separate layout/large-font screenshot scenario below.
        compose.setContent {
            QuickCutTheme {
                Box(Modifier.fillMaxSize().background(LocalCutPalette.current.background).safeDrawingPadding()) {
                    TextEditorScreen(clip, onApply = {}, onCancel = {})
                }
            }
        }
        compose.onNodeWithTag("text-layer-add").performScrollTo().assertIsEnabled()
        capture("stage18_text_empty.png")
        // Replay a physical tap after revealing the button; semantics-only click can be
        // acknowledged even when the emulator has not delivered the touch to the UI.
        compose.onNodeWithTag("text-layer-add").performScrollTo().assertIsEnabled()
        compose.waitForIdle()
        // The actual tapped widget must reveal an editable text field. This outcome,
        // and the following nonblank captures, are the release gates rather than
        // a transient "displayed" flag while the video preview surface reattaches.
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
        compose.runOnUiThread {
            val window = compose.activity.window
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        // IME removal is asynchronous on emulators; do not capture a frame
        // with the pinned action row still covered by the keyboard.
        compose.waitUntil(10_000L) {
            var imeHidden = false
            compose.runOnUiThread {
                val decor = compose.activity.window.decorView
                imeHidden = ViewCompat.getRootWindowInsets(decor)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
            imeHidden
        }
        compose.waitForIdle()
        compose.onNodeWithTag("text-layer-timing").performScrollTo()
        compose.waitUntil(10_000L) {
            try {
                compose.onNodeWithTag("text-layer-timing").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        compose.onNodeWithTag("text-apply").assertIsDisplayed().assertIsEnabled()
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
                        Box(Modifier.fillMaxSize().background(LocalCutPalette.current.background).safeDrawingPadding()) {
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
                val visible = if (name == "stage18_text_styled.png") {
                    // Scrolling to timing hides the black video preview. Verify
                    // the fixed title and pinned apply action instead of requiring
                    // 4% dark pixels in the upper content region.
                    hasVisibleEditorChrome(candidate)
                } else {
                    dark * 25 > scanned
                }
                if (visible) {
                    verified = candidate
                    break
                }
                if (name == "stage18_text_styled.png" && attempt == 6) {
                    val rejected = File(dir, "stage18_text_styled_rejected.png")
                    rejected.outputStream().use {
                        check(candidate.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    Log.w("QuickCutStage18Capture",
                        "Styled screenshot rejected after six real Android captures; " +
                            "lastCandidate=${candidate.width}x${candidate.height} " +
                            "darkUpper=$dark/$scanned evidence=${rejected.absolutePath}")
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

    private fun hasVisibleEditorChrome(bitmap: Bitmap): Boolean {
        if (bitmap.width < 200 || bitmap.height < 200) return false
        val stride = maxOf(1, bitmap.width / 40)
        fun countContrast(from: Int, to: Int): Triple<Int, Int, Int> {
            var dark = 0
            var light = 0
            var pixels = 0
            for (y in from until to step stride) {
                for (x in 0 until bitmap.width step stride) {
                    val color = bitmap.getPixel(x, y)
                    if (Color.red(color) < 96 && Color.green(color) < 96 &&
                        Color.blue(color) < 110) dark++
                    if (Color.red(color) > 190 && Color.green(color) > 190 &&
                        Color.blue(color) > 190) light++
                    pixels++
                }
            }
            return Triple(dark, light, pixels)
        }
        // The header is fixed above the scrolling editor; the bottom action row
        // stays pinned even when the black preview is scrolled completely away.
        val (headerDark, headerLight, headerPixels) =
            countContrast(bitmap.height / 12, bitmap.height / 6)
        val (footerDark, footerLight, footerPixels) =
            countContrast(bitmap.height * 7 / 8, bitmap.height * 39 / 40)
        return headerPixels > 0 && footerPixels > 0 &&
            headerDark * 100 >= headerPixels &&
            footerDark * 20 >= footerPixels &&
            headerLight * 100 >= headerPixels &&
            footerLight * 100 >= footerPixels
    }
}
