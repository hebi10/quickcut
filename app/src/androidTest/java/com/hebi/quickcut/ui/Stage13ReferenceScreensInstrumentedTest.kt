package com.hebi.quickcut.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.SavedProject
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickTrimSettings
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual video fixture screenshots, not placeholder images or fake application records. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class Stage13ReferenceScreensInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val clips = mutableListOf<File>()

    @Before fun prepareVideoFixtures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repeat(2) { index ->
            val file = File(context.cacheDir, "quickcut_stage13_$index.mp4")
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("quickcut_keyframes.mp4").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            clips.add(file)
        }
    }

    @After fun deleteVideoFixtures() {
        clips.forEach { it.delete() }
    }

    @Test fun populatedProjectsToolsAndSettingsHaveRealScreenCaptures() {
        val samples = clips.mapIndexed { index, clip ->
            SavedProject(
                id = "00000000-0000-0000-0000-00000000000${index + 1}",
                title = "테스트 영상 ${index + 1}",
                modifiedAt = System.currentTimeMillis() - index * 3_600_000L,
                clipCount = index + 1,
                thumbnailUri = Uri.fromFile(clip).toString(),
                thumbnailTimeMs = 1_000L,
            )
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))) {
                QuickCutTheme {
                    Box(Modifier.fillMaxSize().background(CutColors.background)) {
                        StudioStartScreen(
                            onQuick = {}, onSelect = {}, onBatch = {},
                            canRestore = false, onRestore = {},
                            quickSettings = QuickTrimSettings(0L, 0L, false),
                            dateMode = SaveDateMode.ORIGINAL,
                            onPreferences = {}, onLicenses = {},
                            projects = samples,
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("테스트 영상 1").assertIsDisplayed()
        capture("stage13_home_populated.png")
        compose.onNodeWithText("프로젝트").performClick()
        compose.onNodeWithText("테스트 영상 2").assertIsDisplayed()
        capture("stage13_projects_populated.png")
        compose.onNodeWithText("도구").performClick()
        compose.onNodeWithText("영상 갤러리").assertIsDisplayed()
        capture("stage13_tools.png")
        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("마지막 사용 설정").assertIsDisplayed()
        capture("stage13_settings.png")
    }

    @Test fun selectedVideoGridCapturesActualFixtureThumbnailsAndState() {
        val uris = clips.map { Uri.fromFile(it).toString() }
        var imported = emptyList<String>()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))) {
                QuickCutTheme {
                    SelectedVideoGallery(uris, onAdd = {},
                        onImport = { imported = it }, onDismiss = {})
                }
            }
        }
        compose.onNodeWithTag("gallery-grid").assertIsDisplayed()
        compose.onNodeWithTag("gallery-item-0").assertIsDisplayed()
        compose.onNodeWithTag("gallery-item-1").assertIsDisplayed()
        compose.onNodeWithText("2개 선택").assertIsDisplayed()
        capture("stage13_gallery_selected.png")
        compose.onNodeWithTag("gallery-item-1").performClick()
        compose.onNodeWithText("1개 선택").assertIsDisplayed()
        capture("stage13_gallery_one_selected.png")
        compose.onNodeWithTag("gallery-import").performClick()
        compose.runOnIdle { assertEquals(listOf(uris[0]), imported) }
    }

    @Test fun largeFontProjectEmptyStateHasVisibleStartAction() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.6f)) {
                    QuickCutTheme {
                        StudioStartScreen(
                            onQuick = {}, onSelect = {}, onBatch = {},
                            canRestore = false, onRestore = {},
                            quickSettings = QuickTrimSettings(0L, 0L, false),
                            dateMode = SaveDateMode.CURRENT,
                            onPreferences = {}, onLicenses = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("프로젝트").performClick()
        compose.onNodeWithText("프로젝트 없음").assertIsDisplayed()
        compose.onNodeWithText("영상 편집 시작하기").performScrollTo().assertIsDisplayed()
        capture("stage13_projects_empty_large_font.png")
    }

    private fun capture(name: String) {
        require(name in setOf(
            "stage13_home_populated.png", "stage13_projects_populated.png",
            "stage13_tools.png", "stage13_settings.png",
            "stage13_gallery_selected.png", "stage13_gallery_one_selected.png",
            "stage13_projects_empty_large_font.png",
        ))
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "design-review")
        check(directory.isDirectory || directory.mkdirs())
        val output = File(directory, name)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        output.outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        check(output.length() > 0L) { "Stage13 capture missing: $name" }
    }
}
