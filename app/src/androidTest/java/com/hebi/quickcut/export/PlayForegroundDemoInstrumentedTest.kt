package com.hebi.quickcut.export

import android.Manifest
import android.app.NotificationManager
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.MainActivity
import com.hebi.quickcut.QuickCutApplication
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.QuickCutPage
import com.hebi.quickcut.media.ExportMethod
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickCutSettings
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayForegroundDemoInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val model get() = (context.applicationContext as QuickCutApplication).editorModel
    private val sources = mutableListOf<Uri>()

    @Before fun prepareOnlySyntheticMedia() {
        assertTrue(Build.VERSION.SDK_INT == 34 || Build.VERSION.SDK_INT == 35)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
            Manifest.permission.POST_NOTIFICATIONS)
        QuickCutSettings(context).apply {
            saveOriginalAction(OriginalAction.KEEP)
            saveSaveDate(SaveDateMode.CURRENT)
        }
        for (name in listOf("QuickCut Demo A.mp4", "QuickCut Demo B.mp4")) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/QuickCut Demo")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
            sources += uri
            context.contentResolver.openOutputStream(uri)!!.use { output ->
                instrumentation.context.assets.open("quickcut_play_demo.mp4").use { it.copyTo(output) }
            }
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
        }
        compose.waitUntil(25_000L) { !model.isRecoveringExport }
        compose.runOnIdle { model.startNew() }
    }

    @After fun cleanDisposableEmulatorData() {
        shell("cmd statusbar collapse")
        if (ExportWorkMonitor.isRunning) compose.runOnUiThread { model.cancelExport() }
        compose.waitUntil(120_000L) { !ExportWorkMonitor.isRunning }
        model.completedUri?.let { context.contentResolver.delete(it, null, null) }
        sources.forEach { context.contentResolver.delete(it, null, null) }
    }

    @Test fun userInitiatedTranscodeContinuesWithVisibleNotificationAndFinishes() {
        Thread.sleep(2_000L)
        compose.onNodeWithText("상세 편집 (분할·연결·순서 변경)").performClick()
        clickSystemItem("QuickCut Demo A.mp4", longClick = true)
        clickSystemItem("QuickCut Demo B.mp4")
        clickSystemButton(setOf("OPEN", "Open", "열기", "선택", "SELECT", "Select"))
        compose.waitUntil(30_000L) { model.page == QuickCutPage.EDITOR }
        compose.runOnIdle {
            assertEquals(2, model.timeline.clips.size)
            assertEquals(2, model.timeline.clips.map { it.uri }.distinct().size)
        }
        Thread.sleep(2_000L)
        compose.onNodeWithText("영상 저장").performClick()
        Thread.sleep(1_000L)
        compose.onNodeWithText("저장 시작").performClick()
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("정확한 위치로 자동 저장").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(1_000L)
        compose.onNodeWithText("정확한 위치로 자동 저장").performClick()
        compose.waitUntil(60_000L) {
            ExportWorkMonitor.isRunning && model.progressPercent?.let { it in 1..80 } == true
        }
        assertTrue(notifications().isNotEmpty())
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1_500L)
        assertTrue("The actual video transcode must continue offscreen", ExportWorkMonitor.isRunning)
        shell("cmd statusbar expand-notifications")
        Thread.sleep(3_000L)
        assertTrue("Ongoing processing must have a notification", notifications().isNotEmpty())
        val directory = File(context.getExternalFilesDir(null), "play-fgs-demo").apply { mkdirs() }
        File(directory, "foreground-service.txt").writeText(shell("dumpsys activity services com.hebi.quickcut"))
        shell("cmd statusbar collapse")
        shell("am start -W -n com.hebi.quickcut/.MainActivity -f 0x10200000")
        compose.waitUntil(180_000L) { !ExportWorkMonitor.isRunning && model.page != QuickCutPage.EXPORTING }
        compose.runOnIdle {
            assertEquals(model.errorMessage, QuickCutPage.COMPLETED, model.page)
            assertEquals(ExportMethod.VIDEO_REENCODE, model.completedExportReport?.method)
            assertNotNull(model.completedExportReport?.optimizationResult)
        }
        MediaReader.validateExport(context, checkNotNull(model.completedUri), 80_000L, expectAudio = true)
        compose.waitUntil(10_000L) { notifications().isEmpty() }
        Thread.sleep(3_000L)
        shell("cmd statusbar expand-notifications")
        Thread.sleep(2_000L)
        assertFalse(ExportWorkMonitor.isRunning)
        shell("cmd statusbar collapse")
        Thread.sleep(2_000L)
        File(directory, "passed.txt").writeText("API ${Build.VERSION.SDK_INT}: real picker, user save, Media3 transcode, background notification, validated 80000ms output and service completion passed.\n")
    }

    private fun notifications() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.filter { it.id == 1001 }

    private fun clickSystemItem(text: String, longClick: Boolean = false) {
        val deadline = System.currentTimeMillis() + 25_000L
        while (System.currentTimeMillis() < deadline) {
            val nodes = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(text).orEmpty()
            for (node in nodes) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isVisibleToUser && current.isEnabled &&
                        (if (longClick) current.isLongClickable else current.isClickable)) {
                        if (current.performAction(if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK
                            else AccessibilityNodeInfo.ACTION_CLICK)) {
                            Thread.sleep(1_000L)
                            return
                        }
                    }
                    current = current.parent
                }
            }
            Thread.sleep(100L)
        }
        error("Document picker did not expose $text")
    }

    private fun clickSystemButton(labels: Set<String>) {
        val deadline = System.currentTimeMillis() + 15_000L
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.isEnabled && node.isClickable &&
                (node.text?.toString() in labels || node.contentDescription?.toString() in labels)) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        while (System.currentTimeMillis() < deadline) {
            val node = find(instrumentation.uiAutomation.rootInActiveWindow)
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            Thread.sleep(100L)
        }
        error("Document picker selection button was unavailable")
    }

    private fun shell(command: String): String {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }
}
