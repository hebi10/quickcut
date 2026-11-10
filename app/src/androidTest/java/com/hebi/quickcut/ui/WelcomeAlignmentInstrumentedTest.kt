package com.hebi.quickcut.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.settings.QuickCutThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class WelcomeAlignmentInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val args get() = InstrumentationRegistry.getArguments()
    private val mode get() = if (args.getString("captureTheme") == "dark")
        QuickCutThemeMode.DARK else QuickCutThemeMode.LIGHT

    @Test fun captureThreeActualOnboardingPagesWithoutChangingSavedPreferences() {
        var completions = 0
        compose.setContent { QuickCutTheme(modeOverride = mode) { WelcomeHost { completions++ } } }
        listOf("01-intro", "02-tools", "03-save").forEachIndexed { index, name ->
            save(name)
            compose.onNodeWithText(if (index == 2) "시작하기" else "다음").performClick()
            compose.runOnIdle { assertEquals(if (index == 2) 1 else 0, completions) }
        }
    }

    @Test fun shortPhoneAtLargeFontCanReadEveryPageAndReachItsAction() {
        var completions = 0
        compose.setContent {
            QuickCutTheme(modeOverride = mode) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 480.dp))) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.6f)) {
                        WelcomeHost { completions++ }
                    }
                }
            }
        }
        listOf("복잡한 과정 없이", "빠른 자르기, 상세 편집", "편집 결과를 확인한 뒤").forEachIndexed { index, text ->
            val action = compose.onNodeWithText(if (index == 2) "시작하기" else "다음")
            action.assertIsDisplayed()
            val explanation = compose.onNodeWithText(text, substring = true)
            explanation.performScrollTo().assertIsDisplayed()
            val bodyBounds = explanation.fetchSemanticsNode().boundsInRoot
            val actionBounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue("The explanation must not overlap the action", bodyBounds.bottom <= actionBounds.top)
            save("short-large-${index + 1}")
            action.performClick()
        }
        compose.runOnIdle { assertEquals(1, completions) }
    }

    private fun save(name: String) {
        compose.onAllNodes(hasText("다음") or hasText("시작하기")).onFirst().assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val phase = args.getString("alignmentPhase") ?: "actual"
        val theme = args.getString("captureTheme") ?: "light"
        val directory = File(compose.activity.filesDir, "design-alignment-captures").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val samples = mutableMapOf<Int, Int>()
        var sampled = 0
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
            val pixel = bitmap.getPixel(x, y)
            samples[pixel] = (samples[pixel] ?: 0) + 1
            sampled++
        }
        check(samples.values.max() < sampled * .98f) { "Blank startup frame is not a valid UI capture: $name" }
        File(directory, "$phase-$theme-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Composable
    private fun WelcomeHost(onFinish: () -> Unit) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) { QuickCutWelcome(onFinish) }
    }
}
