package com.hebi.quickcut.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.batch.BatchViewModel
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.EditorSessionSnapshot
import com.hebi.quickcut.editor.EditorSessionStore
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.editor.QuickCutPage
import com.hebi.quickcut.editor.SingleExportStore
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.MediaReader
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class EditorRecoveryUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun narrowLargeFontRestoreControlsRemainReachable() = checkRestoreControls(320, 640)
    @Test fun expandedFoldLargeFontRestoreControlsRemainReachable() = checkRestoreControls(840, 720)

    private fun checkRestoreControls(width: Int, height: Int) {
        val events = mutableListOf<String>()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.6f)) {
                    QuickCutTheme {
                        RestoreRecoveryDialog("이전 영상을 읽을 수 없습니다. ".repeat(20), true,
                            { events += "retry" }, { events += "reconnect" }, { events += "dismiss" })
                    }
                }
            }
        }
        compose.onNodeWithText("이전 편집 복원").assertIsDisplayed()
        listOf("원본 영상 다시 연결", "다시 시도", "닫기").forEach { label ->
            val action = compose.onNodeWithText(label)
            if (label == "원본 영상 다시 연결") action.performScrollTo()
            action.assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        }
        compose.runOnIdle { assertEquals(listOf("reconnect", "retry", "dismiss"), events) }
    }

    @Test fun restoreWithoutReconnectStillOffersRetryAndDismiss() {
        compose.setContent { QuickCutTheme { RestoreRecoveryDialog("기록을 확인해 주세요.", false, {}, {}, {}) } }
        compose.onNodeWithText("원본 영상 다시 연결").assertDoesNotExist()
        compose.onNodeWithText("다시 시도").assertIsEnabled()
        compose.onNodeWithText("닫기").assertIsEnabled()
    }

    @Test fun importingCancelIsReachableWithLargeFont() {
        var cancelled = false
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.6f)) {
                    QuickCutTheme { ImportingStatus { cancelled = true } }
                }
            }
        }
        compose.onNodeWithText("취소").performScrollTo().assertIsDisplayed().assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun importingSystemBackUsesTheSameCancelAction() {
        var cancelled = false
        compose.setContent { QuickCutTheme { ImportingStatus { cancelled = true } } }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun appRetryDismissAndOpenDocumentReconnectKeepTheSavedEdit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EditorSessionStore(context)
        val previous = store.load()
        val exports = SingleExportStore(context)
        val previousExport = exports.load()
        val source = File.createTempFile("restore_ui", ".mp4", context.cacheDir)
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_keyframes.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val metadata = MediaReader.metadata(context, Uri.fromFile(source))
        val snapshot = EditorSessionSnapshot(EditTimeline(listOf(VideoClip(27L, "content://unavailable/ui",
            metadata.durationMs, muted = true, hasAudio = metadata.hasAudio).withRangeUs(123_456L, 4_567_890L))),
            27L, 321L, false)
        var model: EditorViewModel? = null
        var batch: BatchViewModel? = null
        var pickerLaunched = false
        val visible = mutableStateOf(true)
        try {
            exports.clear()
            store.save(snapshot)
            compose.runOnUiThread {
                model = EditorViewModel(context.applicationContext as Application)
                batch = BatchViewModel(context.applicationContext as Application)
            }
            val editor = requireNotNull(model)
            val batchModel = requireNotNull(batch)
            val registry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                    input: I, options: ActivityOptionsCompat?) {
                    val intent = contract.createIntent(compose.activity, input)
                    assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
                    assertArrayEquals(arrayOf("video/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
                    pickerLaunched = true
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(source)))
                }
            }
            val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    if (visible.value) QuickCutTheme { QuickCutApp(editor, batchModel) }
                }
            }
            if (compose.onAllNodesWithText("다음").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("다음").performClick()
                compose.onNodeWithText("다음").performClick()
                compose.onNodeWithText("시작하기").performClick()
            }
            compose.onNodeWithText("이전 편집 이어서 진행").performScrollTo().performClick()
            compose.waitUntil(25_000L) { editor.restoreRecoveryNotice != null && editor.page == QuickCutPage.PICKER }
            compose.onNodeWithText("닫기").performClick()
            compose.runOnIdle {
                assertNull(editor.restoreRecoveryNotice)
                assertEquals(snapshot, store.load())
                assertTrue(editor.hasRestorableSession)
            }
            compose.onNodeWithText("이전 편집 이어서 진행").performScrollTo().performClick()
            compose.waitUntil(25_000L) { editor.restoreRecoveryNotice != null && editor.page == QuickCutPage.PICKER }
            compose.onNodeWithText("다시 시도").performClick()
            compose.waitUntil(25_000L) { editor.page == QuickCutPage.PICKER && editor.restoreRecoveryNotice != null }
            assertEquals(snapshot, store.load())
            compose.onNodeWithText("원본 영상 다시 연결").performScrollTo().performClick()
            compose.waitUntil(25_000L) { editor.page == QuickCutPage.EDITOR }
            compose.runOnIdle {
                assertTrue(pickerLaunched)
                assertEquals(snapshot.timeline.clips.map { it.copy(uri = Uri.fromFile(source).toString()) }, editor.timeline.clips)
                assertEquals(snapshot.selectedId, editor.selectedId)
                assertNull(editor.restoreRecoveryNotice)
            }
        } finally {
            compose.runOnUiThread { visible.value = false }
            compose.waitForIdle()
            compose.runOnUiThread {
                model?.cancelImport()
                ViewModelStore().apply {
                    model?.let { put("editor", it) }
                    batch?.let { put("batch", it) }
                }.clear()
            }
            previous?.let(store::save) ?: store.clear()
            previousExport?.let(exports::save) ?: exports.clear()
            source.delete()
        }
    }
}
