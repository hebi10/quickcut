package com.hebi.quickcut.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickTrimSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real start layout without importing media or starting an export. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class StudioDesignInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun narrowPhoneKeepsAllStartActionsReadableAndReachable() {
        checkHome(width = 320, height = 640, fontScale = 1f, screenshotName = "phone")
    }

    @Test
    fun narrowPhoneWithLargeFontKeepsAllStartActionsReadableAndReachable() {
        checkHome(width = 320, height = 640, fontScale = 1.6f, screenshotName = "phone_font_160")
    }

    @Test
    fun landscapeKeepsAllStartActionsReadableAndReachable() {
        checkHome(width = 640, height = 360, fontScale = 1f, screenshotName = "landscape")
    }

    @Test
    fun expandedFoldKeepsAllStartActionsReadableAndReachable() {
        checkHome(width = 840, height = 720, fontScale = 1f, screenshotName = "fold")
    }

    @Test
    fun restoreActionIsAbsentWithoutASavedSession() {
        showHome(width = 320, height = 640, fontScale = 1f, canRestore = false)

        composeRule.onNodeWithText("이전 편집 이어서 진행").assertDoesNotExist()
        composeRule.onNode(hasText("설정 변경") and hasClickAction())
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    private fun checkHome(width: Int, height: Int, fontScale: Float, screenshotName: String) {
        val events = mutableListOf<String>()
        showHome(width, height, fontScale, canRestore = true, events = events)
        captureReviewImage("studio_home_${screenshotName}_top.png")

        // Losing the scroll container, constraining a wrapped button's height, or wiring a
        // mode to the wrong callback must fail even when the first viewport looks correct.
        listOf(
            "빠른 자르기 · 영상 1개",
            "일괄 편집 · 여러 영상",
            "상세 편집 (분할·연결·순서 변경)",
            "이전 편집 이어서 진행",
            "설정 변경",
            "오픈소스 라이선스",
        ).forEach { label ->
            val action = composeRule.onNode(hasText(label) and hasClickAction())
                .performScrollTo().assertIsDisplayed().assertIsEnabled()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            assertLabelIsNotClipped(label)
            if (label == "설정 변경") {
                captureReviewImage("studio_home_${screenshotName}_settings.png")
            }
            action.performClick()
        }

        composeRule.runOnIdle {
            assertEquals(
                listOf("quick", "batch", "detailed", "restore", "preferences", "licenses"),
                events,
            )
        }
    }

    private fun showHome(
        width: Int,
        height: Int,
        fontScale: Float,
        canRestore: Boolean,
        events: MutableList<String> = mutableListOf(),
    ) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    QuickCutTheme {
                        Box(
                            Modifier.fillMaxSize().background(CutColors.background)
                                .testTag("studio-design-review"),
                        ) {
                            StudioStartScreen(
                                onQuick = { events += "quick" },
                                onSelect = { events += "detailed" },
                                onBatch = { events += "batch" },
                                canRestore = canRestore,
                                onRestore = { events += "restore" },
                                quickSettings = QuickTrimSettings(4_000L, 1_500L, true),
                                dateMode = SaveDateMode.ORIGINAL,
                                onPreferences = { events += "preferences" },
                                onLicenses = { events += "licenses" },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun assertLabelIsNotClipped(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayouts ->
                assertTrue("The action label must expose its text layout: $label", getLayouts(layouts))
            }
        assertTrue("The action label must have a text layout: $label", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertFalse("The action label is clipped horizontally: $label", layout.didOverflowWidth)
            assertFalse("The action label is clipped vertically: $label", layout.didOverflowHeight)
        }
    }

    private fun captureReviewImage(name: String) {
        // Screenshot support varies by runner. Layout, readability and navigation assertions
        // above remain mandatory; a PixelCopy/storage limitation only skips this artifact.
        runCatching {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(checkNotNull(context.getExternalFilesDir(null)), "design-review")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, name)
            val bitmap = composeRule.onNodeWithTag("studio-design-review")
                .captureToImage().asAndroidBitmap()
            output.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            Log.i("StudioDesignTest", "Saved screenshot: ${output.absolutePath}")
        }.onFailure { error ->
            Log.w("StudioDesignTest", "Could not capture $name", error)
        }
    }
}
