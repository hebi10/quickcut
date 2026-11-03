package com.hebi.quickcut.export

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ContentValues
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
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
        for (name in listOf("Demo-A.mp4", "Demo-B.mp4")) {
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
        dismissLauncherStartupDialog()
    }

    @After fun cleanDisposableEmulatorData() {
        saveUiDiagnostic()
        shell("cmd statusbar collapse")
        if (ExportWorkMonitor.isRunning) compose.runOnUiThread { model.cancelExport() }
        compose.waitUntil(120_000L) { !ExportWorkMonitor.isRunning }
        model.completedUri?.let { context.contentResolver.delete(it, null, null) }
        sources.forEach { context.contentResolver.delete(it, null, null) }
    }

    @Test fun userInitiatedTranscodeContinuesWithVisibleNotificationAndFinishes() {
        Thread.sleep(2_000L)
        dismissLauncherStartupDialog()
        compose.onNodeWithText("상세 편집 (분할·연결·순서 변경)").performClick()
        clickSystemItem("Demo-A.mp4", longClick = true)
        clickSystemItem("Demo-B.mp4")
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
        val serviceDump = shell("dumpsys activity services com.hebi.quickcut")
        File(directory, "foreground-service.txt").writeText(serviceDump)
        assertTrue("The export service must be running in the foreground", exportService()?.foreground == true)
        val foregroundLine = serviceDump.lineSequence().firstOrNull {
            it.contains("isForeground=true") && it.contains("foregroundId=1001")
        } ?: error("No active export foreground service in the system dump")
        val type = checkNotNull(Regex("types=(?:0x)?([0-9A-Fa-f]+)").find(foregroundLine))
            .groupValues[1].toLong(16)
        val expectedType = if (Build.VERSION.SDK_INT >= 35)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        assertEquals("Foreground type must match the actual Android version", expectedType.toLong(), type)
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
        compose.waitUntil(10_000L) { exportService() == null }
        File(directory, "service-after-completion.txt")
            .writeText(shell("dumpsys activity services com.hebi.quickcut"))
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

    @Suppress("DEPRECATION")
    private fun exportService() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).singleOrNull {
            it.service.className == "com.hebi.quickcut.export.ExportWorkService"
        }

    private fun clickSystemItem(text: String, longClick: Boolean = false) {
        val deadline = System.currentTimeMillis() + 25_000L
        while (System.currentTimeMillis() < deadline) {
            dismissLauncherStartupDialog()
            val nodes = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(text).orEmpty()
            for (node in nodes) {
                if (node.text?.toString() == text && node.isVisibleToUser && node.isEnabled) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        val x = bounds.centerX()
                        val y = bounds.centerY()
                        shell(if (longClick) "input swipe $x $y $x $y 1200" else "input tap $x $y")
                        Thread.sleep(1_000L)
                        return
                    }
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
            dismissLauncherStartupDialog()
            val node = find(instrumentation.uiAutomation.rootInActiveWindow)
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            Thread.sleep(100L)
        }
        error("Document picker selection button was unavailable")
    }

    private fun dismissLauncherStartupDialog() {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        if (root.findAccessibilityNodeInfosByText("Pixel Launcher isn't responding").isEmpty()) return
        val close = root.findAccessibilityNodeInfosByText("Close app").firstOrNull {
            it.text?.toString() == "Close app" && it.isClickable
        } ?: error("The unrelated launcher startup dialog has no close control")
        check(close.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(500L)
    }

    private fun saveUiDiagnostic() {
        val directory = File(context.getExternalFilesDir(null), "play-fgs-demo").apply { mkdirs() }
        val lines = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30) return
            val bounds = Rect().also(node::getBoundsInScreen)
            lines += "$depth ${node.packageName} ${node.className} text=${node.text} description=${node.contentDescription} visible=${node.isVisibleToUser} clickable=${node.isClickable} longClickable=${node.isLongClickable} bounds=$bounds"
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow, 0)
        File(directory, "final-ui.txt").writeText(lines.joinToString("\n"))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "final-ui.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun shell(command: String): String {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }
}
