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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.AudioAdjustment
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class Stage17AudioScreensInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var input: File

    @Before fun setupFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        input = File(instrumentation.targetContext.cacheDir, "stage17_audio_ui_source.mp4")
        instrumentation.context.assets.open("quickcut_keyframes.mp4").use { src ->
            input.outputStream().use { output -> src.copyTo(output) }
        }
    }

    @After fun cleanupFixture() {
        input.delete()
    }

    @Test fun defaultFadeAndMutedAudioStatesProduceActualAndroidScreens() {
        val uri = Uri.fromFile(input).toString()
        var clip by mutableStateOf(VideoClip(1, uri, 8_000, hasAudio = true))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))) {
                QuickCutTheme {
                    AudioEditorScreen(clip = clip, onApply = { _, _ -> }, onCancel = {})
                }
            }
        }
        compose.onNodeWithText("원본 음량 · 100%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("audio-apply").assertIsDisplayed()
        capture("stage17_audio_default.png")

        compose.runOnIdle {
            clip = clip.copy(id = 2L, audio = AudioAdjustment(45, 1_000, 1_000))
        }
        compose.onNodeWithText("원본 음량 · 45%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("페이드 인 · 00:01.0").performScrollTo().assertIsDisplayed()
        capture("stage17_audio_fades.png")

        compose.runOnIdle {
            clip = clip.copy(id = 3L, muted = true)
        }
        compose.onNodeWithText(
            "현재 클립이 음소거 상태입니다. 스위치를 끄면 소리 조절을 다시 사용할 수 있습니다."
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("audio-volume").assertIsNotEnabled()
        compose.onNodeWithTag("audio-mute-toggle").assertIsEnabled()
        capture("stage17_audio_muted.png")
        compose.onNodeWithTag("audio-mute-toggle").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("audio-volume").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("audio-apply").assertIsEnabled()
    }

    @Test fun tabletLargeFontCanStillReachPinnedApplyButton() {
        val uri = Uri.fromFile(input).toString()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(860.dp, 640.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.6f)) {
                    QuickCutTheme {
                        AudioEditorScreen(
                            VideoClip(1, uri, 8_000, hasAudio = true),
                            onApply = { _, _ -> }, onCancel = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("audio-apply").assertIsDisplayed()
        capture("stage17_audio_largefont.png")
    }

    private fun capture(name: String) {
        check(name in setOf(
            "stage17_audio_default.png", "stage17_audio_fades.png",
            "stage17_audio_muted.png", "stage17_audio_largefont.png",
        ))
        compose.waitForIdle()
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "design-review")
        check(folder.isDirectory || folder.mkdirs())
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Android 화면 캡처 실패")
        try {
            File(folder, name).outputStream().use {
                check(screen.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            screen.recycle()
        }
        check(File(folder, name).length() > 0L)
    }
}
