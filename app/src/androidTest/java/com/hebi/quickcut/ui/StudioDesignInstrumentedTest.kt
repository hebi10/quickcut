package com.hebi.quickcut.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
            assertEquals("The complete action label must be laid out", label, layout.layoutInput.text.text)
            assertTrue("The action label must have a visible line: $label", layout.lineCount > 0)
            // Spaces consumed at a soft wrap have no painted glyph and can extend past a line.
            val visibleOffsets = label.indices.filterNot { label[it].isWhitespace() }
            val characterBounds = visibleOffsets.map(layout::getBoundingBox)
            val glyphBounds = Rect(
                characterBounds.minOf { it.left }, characterBounds.minOf { it.top },
                characterBounds.maxOf { it.right }, characterBounds.maxOf { it.bottom },
            )
            val lines = (0 until layout.lineCount).joinToString { line ->
                "$line:[${layout.getLineLeft(line)},${layout.getLineTop(line)}," +
                    "${layout.getLineRight(line)},${layout.getLineBottom(line)}]"
            }
            val metrics = "label=$label size=${layout.size} paragraph=" +
                "${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                "didOverflowWidth=${layout.didOverflowWidth} " +
                "didOverflowHeight=${layout.didOverflowHeight} glyphBounds=$glyphBounds lines=$lines"
            Log.i("StudioDesignTest", metrics)

            // Compose 1.11's String-text semantics rebuild a MultiParagraph at the available
            // width but retain the smaller measured layoutSize. didOverflowWidth then reports
            // unused paragraph allocation as overflow. Check the actual characters and lines.
            // Their coordinates are floats, while layout.size is integral physical pixels;
            // allow at most one physical pixel of edge rounding, independent of density.
            val roundingPx = 1f
            val width = layout.size.width.toFloat()
            val height = layout.size.height.toFloat()
            assertFalse("The action label must not truncate lines: $metrics",
                layout.multiParagraph.didExceedMaxLines)
            assertEquals("The final visible line must consume the complete action label: $metrics",
                label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            assertTrue("The action label is clipped vertically: $metrics",
                layout.multiParagraph.height.isFinite() &&
                    layout.multiParagraph.height <= height + roundingPx)
            repeat(layout.lineCount) { line ->
                assertFalse("The action label must not be ellipsized: $metrics",
                    layout.isLineEllipsized(line))
                val left = layout.getLineLeft(line)
                val right = layout.getLineRight(line)
                val top = layout.getLineTop(line)
                val bottom = layout.getLineBottom(line)
                assertTrue("The action label line is clipped horizontally: $metrics",
                    left.isFinite() && right.isFinite() &&
                        left >= -roundingPx && right <= width + roundingPx)
                assertTrue("The action label line is clipped vertically: $metrics",
                    top.isFinite() && bottom.isFinite() &&
                        top >= -roundingPx && bottom <= height + roundingPx)
            }
            characterBounds.forEachIndexed { index, bounds ->
                val offset = visibleOffsets[index]
                assertTrue("The action label character $offset is clipped horizontally: $metrics",
                    bounds.left.isFinite() && bounds.right.isFinite() &&
                        bounds.left >= -roundingPx && bounds.right <= width + roundingPx)
                assertTrue("The action label character $offset is clipped vertically: $metrics",
                    bounds.top.isFinite() && bounds.bottom.isFinite() &&
                        bounds.top >= -roundingPx && bounds.bottom <= height + roundingPx)
            }
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
            // Gradle uninstalls the app after instrumentation, removing external-files images.
            // Copy the review artifacts to a shell-owned directory before that cleanup.
            require(name.matches(Regex("studio_home_(phone|phone_font_160|landscape|fold)_(top|settings)\\.png")))
            require(output.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
            val destination = "/data/local/tmp/quickcut-design-review/$name"
            fun shell(command: String): String {
                val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand(command)
                return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader()
                    .use { it.readText().trim() }
            }
            shell("mkdir -p /data/local/tmp/quickcut-design-review")
            shell("cp ${output.absolutePath} $destination")
            val preservedBytes = shell("wc -c $destination").substringBefore(' ').toLongOrNull()
            check(output.length() > 0L && preservedBytes == output.length()) {
                "Could not preserve complete screenshot: $destination"
            }
            Log.i("StudioDesignTest", "Preserved screenshot: $destination")
        }.onFailure { error ->
            Log.w("StudioDesignTest", "Could not capture $name", error)
        }
    }
}
