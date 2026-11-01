package com.hebi.quickcut.batch

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.ExportMethod
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.settings.BatchDraftSettings
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.ui.QuickCutTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class BatchFastAdjustmentInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var model: BatchViewModel
    private lateinit var source: File
    private lateinit var settings: QuickCutSettings
    private lateinit var previousDraft: BatchDraftSettings
    private lateinit var previousDate: SaveDateMode
    private lateinit var previousAction: OriginalAction
    private lateinit var sessionStore: BatchSessionStore
    private var previousSession: BatchSessionSnapshot? = null
    private val extraSources = mutableListOf<File>()

    @Before
    fun importNonKeyframeTrim() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            // UI exports use an Activity context and may request notifications.
            // Grant this fixture permission before composing so the system dialog
            // cannot hide the result hierarchy that this test must inspect.
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
            assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        }
        settings = QuickCutSettings(context)
        previousDraft = settings.loadBatch()
        previousDate = settings.loadSaveDate()
        previousAction = settings.loadOriginalAction()
        sessionStore = BatchSessionStore(context)
        previousSession = sessionStore.load()
        sessionStore.clear()
        source = File(context.cacheDir, "quickcut_batch_adjustment.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("quickcut_keyframes.mp4").use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
        composeRule.runOnUiThread {
            model = BatchViewModel(context.applicationContext as Application)
            model.draftName = "앞 3초 삭제"
            model.draftMode = BatchClipMode.REMOVE_EDGES
            model.draftLeadingSeconds = "3"
            model.draftTrailingSeconds = "0"
            model.draftMute = false
            model.saveDateMode = SaveDateMode.CURRENT
            model.addVideos(context, listOf(Uri.fromFile(source)))
        }
        composeRule.waitUntil(30_000L) { !model.isImporting && model.files.size == 1 }
        composeRule.setContent {
            QuickCutTheme { BatchScreen(model, {}, {}, { _, _, _ -> }) }
        }
    }

    @After
    fun cleanUp() {
        if (::model.isInitialized) {
            composeRule.runOnUiThread { model.cancel() }
            composeRule.waitUntil(120_000L) { !model.isRunning }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            model.files.mapNotNull(BatchFile::savedOutputUri).forEach { output ->
                runCatching { context.contentResolver.delete(Uri.parse(output), null, null) }
            }
            composeRule.runOnUiThread {
                ViewModelStore().apply { put("batch", model) }.clear()
            }
        }
        if (::sessionStore.isInitialized) {
            previousSession?.let(sessionStore::save) ?: sessionStore.clear()
        }
        if (::settings.isInitialized) {
            settings.saveBatch(previousDraft)
            settings.saveSaveDate(previousDate)
            settings.saveOriginalAction(previousAction)
        }
        if (::source.isInitialized) source.delete()
        extraSources.forEach(File::delete)
    }

    @Test
    fun nonKeyframeBatchOffersExactSaveAndExplicitlyReviewedFastSave() {
        openReview()
        composeRule.onNodeWithTag("batch-review-list").performScrollToNode(hasText("전체 자동 저장"))
        composeRule.onNodeWithText("전체 자동 저장").assertIsEnabled()
        composeRule.onNodeWithText("고속 저장 가능한 영상만").assertDoesNotExist()
        composeRule.onNodeWithText("위치 조정 후 고속 저장").assertDoesNotExist()
        composeRule.onNodeWithText("추천 위치로 1개 고속 저장").assertIsEnabled()
        composeRule.onNodeWithTag("batch-review-list").performScrollToNode(
            hasText("총 1초가 추가로 삭제됩니다.", substring = true))
        composeRule.onNodeWithText("총 1초가 추가로 삭제됩니다.", substring = true).assertExists()
        composeRule.runOnIdle {
            assertFalse(model.isRunning)
            assertEquals("3", model.draftLeadingSeconds)
        }
    }

    @Test
    fun automaticReviewExplainsTheExactCutAndCanReturnToSettings() {
        openReview()
        composeRule.onNode(hasText("각 영상의 앞 3초와 뒤 0초를 자릅니다.") and
            hasAnyAncestor(isDialog())).assertExists()
        composeRule.runOnIdle {
            assertFalse(model.isRunning)
            assertEquals("3", model.draftLeadingSeconds)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "quickcut-fast-review.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        composeRule.onNodeWithText("설정으로 돌아가기").performClick()
        composeRule.onNodeWithText("자동 저장 확인").assertDoesNotExist()
        assertFalse(model.isRunning)
        assertEquals(3_000L, model.previewTemplate()!!.leadingMs)
    }

    @Test
    fun changedSettingsFilesAndRetryModeCannotUseAnEarlierReview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsReview = inspect()
        composeRule.runOnUiThread {
            model.draftMute = true
            model.startReviewed(context, settingsReview, false, ExportMode.FAST_ONLY, true)
        }
        assertFalse(model.isRunning)
        assertTrue(model.notification!!.contains("다시 분석"))

        val retryReview = inspect()
        composeRule.runOnUiThread {
            model.startReviewed(context, retryReview, true, ExportMode.FAST_ONLY, true)
        }
        assertFalse(model.isRunning)

        val filesReview = inspect()
        composeRule.runOnUiThread { model.removeVideo(model.files.single().uri) }
        composeRule.waitUntil(30_000L) { !model.isImporting }
        composeRule.runOnUiThread {
            model.startReviewed(context, filesReview, false, ExportMode.FAST_ONLY, true)
        }
        assertFalse(model.isRunning)
        assertTrue(model.files.isEmpty())
    }

    @Test
    fun changingThenRestoringASettingStillDiscardsEveryReviewedChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val review = inspect()
        val choices = review.reviewedFiles.associate { it.uri to BatchExportChoice.RECOMMENDED }
        composeRule.runOnUiThread {
            model.draftMute = true
            model.draftMute = false
            model.startReviewed(context, review, false, choices)
        }
        assertFalse(model.isRunning)
        assertTrue(model.notification!!.contains("다시 분석"))
    }

    @Test
    fun incompleteAndForeignChoicesDoNotStartAnyOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val incomplete = inspect()
        composeRule.runOnUiThread { model.startReviewed(context, incomplete, false, emptyMap()) }
        assertFalse(model.isRunning)
        assertTrue(model.files.all { it.savedOutputUri == null })
        val foreign = inspect()
        val choices = BatchExportChoicePolicy.defaults(foreign.reviewedFiles) +
            ("content://foreign" to BatchExportChoice.SKIP)
        composeRule.runOnUiThread { model.startReviewed(context, foreign, false, choices) }
        assertFalse(model.isRunning)
        assertTrue(model.files.all { it.savedOutputUri == null })
    }

    @Test
    fun refusedFastReviewExplainsConversionAndDisablesRecommendation() {
        val review = inspect()
        val file = review.reviewedFiles.single().copy(reencodeRequired = true, fastAvailable = false)
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                QuickCutTheme { BatchReviewDialog(review.copy(reviewedFiles = listOf(file)), null, {}, {}, {}, {}) }
            }
        }
        composeRule.onNodeWithTag("batch-review-list").performScrollToNode(
            hasTestTag("batch-review-${file.uri}-choice"))
        composeRule.onNodeWithTag("batch-review-${file.uri}-choice")
            .assertTextContains("영상 변환 허용", substring = true).performClick()
        composeRule.onNodeWithText("추천 위치").assertIsNotEnabled()
        composeRule.onNodeWithText("정확한 위치 · 영상 변환 허용").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("batch-review-recommended-submit").assertDoesNotExist()
        assertFalse(model.isRunning)
        assertTrue(model.files.single().savedOutputUri == null)
    }

    @Test
    fun reviewKeepsBothSaveActionsInFixedFooter() {
        assertActualGeometryConfiguration()
        val review = inspect()
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                QuickCutTheme { BatchReviewDialog(review, null, {}, {}, {}, {}) }
            }
        }
        composeRule.onNodeWithTag("batch-review-submit").performScrollTo().assertIsDisplayed().assertIsEnabled()
        // Both primary actions must remain visible together, outside the scrolling file list.
        composeRule.onNodeWithTag("batch-review-recommended-submit").assertIsDisplayed().assertIsEnabled()
        composeRule.onNode(hasTestTag("batch-review-recommended-submit") and
            hasAnyAncestor(hasTestTag("batch-review-footer"))).assertExists()
        composeRule.onNode(hasTestTag("batch-review-submit") and
            hasAnyAncestor(hasTestTag("batch-review-footer"))).assertExists()
        val exact = composeRule.onNodeWithTag("batch-review-submit").fetchSemanticsNode().boundsInRoot
        val recommended = composeRule.onNodeWithTag("batch-review-recommended-submit").fetchSemanticsNode().boundsInRoot
        assertFalse("Footer actions must not overlap", exact.overlaps(recommended))
        captureBatchGeometry("review", "batch-review-dialog")
        assertFalse(model.isRunning)
        assertTrue(model.files.single().savedOutputUri == null)
    }

    @Test
    fun templateManagerRemainsUsable() {
        assertActualGeometryConfiguration()
        composeRule.onNodeWithTag("batch-template-manager").performScrollTo().performClick()
        composeRule.onNodeWithTag("batch-template-direct").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("새 템플릿으로 저장").performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("닫기").assertIsDisplayed().assertIsEnabled()
        captureBatchGeometry("templates", "batch-template-dialog")
        composeRule.onNodeWithText("닫기").performClick()
        composeRule.onNodeWithTag("batch-template-dialog").assertDoesNotExist()
        assertFalse(model.isRunning)
        assertTrue(model.files.single().savedOutputUri == null)
    }

    private val geometryStage: String
        get() = (InstrumentationRegistry.getArguments().getString("quickcutGeometryStage") ?: "default")
            .also { require(it in setOf("default", "narrow", "wide")) }

    private fun assertActualGeometryConfiguration() {
        val arguments = InstrumentationRegistry.getArguments()
        val configuration = composeRule.activity.resources.configuration
        val metrics = composeRule.activity.resources.displayMetrics
        if (geometryStage != "default") {
            val width = requireNotNull(arguments.getString("quickcutExpectedWidthDp")).toInt()
            val font = requireNotNull(arguments.getString("quickcutExpectedFontScale")).toFloat()
            assertEquals("Fresh Activity width in dp", width, configuration.screenWidthDp)
            assertEquals("Fresh Activity font scale", font, configuration.fontScale, 0.01f)
            assertEquals("Actual resource density", 320, configuration.densityDpi)
            assertEquals("Actual display density", 320, metrics.densityDpi)
        }
        // Activity height excludes system insets on some API levels; record it, do not
        // confuse it with the complete wm display height.
        assertTrue(configuration.screenWidthDp > 0 && configuration.screenHeightDp > 0)
    }

    private fun captureBatchGeometry(kind: String, tag: String) {
        val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val resources = composeRule.activity.resources
        assertTrue("Dialog must have actual positive bounds", bounds.width > 0 && bounds.height > 0)
        assertTrue("Dialog must fit the actual display width", bounds.width <= resources.displayMetrics.widthPixels + 1)
        if (kind == "review") {
            assertTrue("Review dialog keeps its 680dp cap", bounds.width <= 680 * resources.displayMetrics.density + 1)
        }
        val capture = runCatching {
            val stem = "batch_${kind}_${geometryStage}"
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(checkNotNull(context.getExternalFilesDir(null)), "design-review")
            check(directory.isDirectory || directory.mkdirs())
            fun shell(command: String): String {
                val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
                return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
            }
            fun preserve(output: File) {
                require(output.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
                val destination = "/data/local/tmp/quickcut-design-review/${output.name}"
                shell("mkdir -p /data/local/tmp/quickcut-design-review")
                shell("cp ${output.absolutePath} $destination")
                check(output.length() > 0 && shell("wc -c $destination").substringBefore(' ').toLongOrNull() == output.length())
                Log.i("BatchDesignTest", "Preserved geometry evidence: $destination")
            }
            val dialog = composeRule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            val image = File(directory, "$stem.png")
            image.outputStream().use { check(dialog.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            preserve(image)
            val device = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                val fullImage = File(directory, "${stem}_device.png")
                fullImage.outputStream().use { check(device.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                preserve(fullImage)
                val configuration = resources.configuration
                val evidence = JSONObject()
                    .put("stage", geometryStage)
                    .put("screenWidthDp", configuration.screenWidthDp)
                    .put("screenHeightDp", configuration.screenHeightDp)
                    .put("fontScale", configuration.fontScale.toDouble())
                    .put("densityDpi", configuration.densityDpi)
                    .put("displayDensity", resources.displayMetrics.density.toDouble())
                    .put("displayWidthPx", device.width)
                    .put("displayHeightPx", device.height)
                    .put("dialogBoundsInWindowPx", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                val output = File(directory, "${stem}_configuration.json")
                output.writeText(evidence.toString(2) + "\n")
                preserve(output)
            } finally {
                device.recycle()
            }
        }
        // Default full-suite captures remain optional; focused stage evidence is mandatory.
        if (geometryStage != "default") capture.getOrThrow()
        else capture.onFailure { Log.w("BatchDesignTest", "Could not capture default $kind geometry", it) }
    }

    @Test
    fun fileReviewStartsRecommendedAndAllowsAnExplicitSkip() {
        openReview()
        val uri = model.files.single().uri
        composeRule.onNodeWithTag("batch-review-$uri-choice").performScrollTo().assertTextContains("추천 위치", substring = true)
        composeRule.onNodeWithTag("batch-review-$uri-choice").performClick()
        composeRule.onNodeWithText("제외").performClick()
        composeRule.onNodeWithText("전체 제외 적용").performClick()
        composeRule.waitUntil(30_000L) { model.runState == BatchRunState.FINISHED }
        assertEquals(BatchFileStatus.SKIPPED, model.files.single().status)
        assertEquals("사용자가 제외한 영상입니다.", model.files.single().reason)
        assertTrue(model.files.single().savedOutputUri == null)
    }

    @Test
    fun previewDoesNotStartOrChangeTheRecommendedDefault() {
        openReview()
        val uri = model.files.single().uri
        composeRule.onNodeWithTag("batch-review-list").performScrollToNode(
            hasTestTag("batch-review-$uri-preview"))
        composeRule.onNode(hasText("미리보기 · 요청/추천 비교") and
            hasAnyAncestor(hasTestTag("batch-review-$uri-preview"))).performClick()
        composeRule.onNodeWithTag("batch-review-list").performScrollToNode(
            hasTestTag("batch-review-$uri-choice"))
        composeRule.onNodeWithTag("batch-review-$uri-choice").assertTextContains("추천 위치", substring = true)
        composeRule.onNodeWithTag("batch-review-submit").assertIsEnabled()
        assertFalse(model.isRunning)
        assertTrue(model.files.single().savedOutputUri == null)
    }

    @Test
    fun unavailableFileShowsItsReasonAndDisablesUnsupportedSelections() {
        composeRule.runOnUiThread {
            model.draftLeadingSeconds = "120"
        }
        openReview()
        val uri = model.files.single().uri
        composeRule.onNodeWithTag("batch-review-list").performScrollToNode(
            hasTestTag("batch-review-$uri-choice"))
        composeRule.onNodeWithTag("batch-review-$uri-choice").assertTextContains("제외", substring = true).performClick()
        composeRule.onNodeWithText("정확한 위치").assertIsNotEnabled()
        composeRule.onNodeWithText("추천 위치").assertIsNotEnabled()
        composeRule.onNodeWithText("제외").performClick()
        composeRule.onNodeWithText("저장 불가 · 앞뒤 구간을 자를 만큼 영상이 길지 않습니다.").assertExists()
        composeRule.onNodeWithText("전체 제외 적용").assertIsEnabled()
    }

    @Test
    fun mixedChoicesUsePerFileRangesAndOnlySavedSourcesCanBeDeleted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extraUris = (2..3).map { index ->
            val file = File(context.cacheDir, "batch_choice_$index.mp4")
            extraSources += file
            source.copyTo(file, overwrite = true)
            Uri.fromFile(file)
        }
        composeRule.runOnUiThread {
            model.originalAction = OriginalAction.TRASH
            model.addVideos(context, extraUris)
        }
        composeRule.waitUntil(30_000L) { !model.isImporting && model.files.size == 3 }
        val review = inspect()
        val rows = review.reviewedFiles
        assertEquals(3, rows.count { it.canRecommend })
        val choices = mapOf(rows[0].uri to BatchExportChoice.EXACT,
            rows[1].uri to BatchExportChoice.RECOMMENDED,
            rows[2].uri to BatchExportChoice.SKIP)
        composeRule.runOnUiThread { model.startReviewed(context, review, false, choices) }
        composeRule.waitUntil(120_000L) { model.runState == BatchRunState.FINISHED }
        assertEquals(listOf(BatchFileStatus.SAVED, BatchFileStatus.SAVED, BatchFileStatus.SKIPPED),
            model.files.map { it.status })
        assertEquals(rows[0].requested!!.totalDurationMs, model.files[0].outputExpectedDurationMs)
        assertEquals(rows[1].suggestion!!.adjusted.totalDurationMs, model.files[1].outputExpectedDurationMs)
        assertTrue(model.files[1].exportReport!!.method in
            setOf(ExportMethod.ORIGINAL_COPY, ExportMethod.AUDIO_REENCODE))
        assertEquals(setOf(rows[0].uri, rows[1].uri), model.originalCandidates.toSet())
        assertTrue(model.files[2].savedOutputUri == null)
        val outputs = model.files.take(2).map { it.savedOutputUri }
        val retry = inspect(retryOnly = true)
        assertEquals(listOf(rows[2].uri), retry.reviewedFiles.map { it.uri })
        composeRule.runOnUiThread {
            model.startReviewed(context, retry, true, mapOf(rows[2].uri to BatchExportChoice.SKIP))
        }
        composeRule.waitUntil(30_000L) { model.runState == BatchRunState.FINISHED }
        assertEquals(outputs, model.files.take(2).map { it.savedOutputUri })
        assertTrue(model.originalCandidates.isEmpty())
    }

    @Test
    fun changedInputsDuringAnalysisInvalidateThePriorAndInProgressReviews() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prior = inspect()
        val error = runCatching {
            runBlocking {
                withContext(Dispatchers.Main) {
                    model.inspectExport(context, false) { _, _ -> model.draftLeadingSeconds = "4" }
                }
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        composeRule.runOnUiThread {
            model.draftLeadingSeconds = "3"
            model.startReviewed(context, prior, false, ExportMode.FAST_ONLY, true)
        }
        assertFalse(model.isRunning)
    }

    @Test
    fun retryAndRestartRequireASeparateAdjustmentReview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val review = inspect()
        composeRule.runOnUiThread {
            model.startReviewed(context, review, false, ExportMode.FAST_ONLY)
        }
        composeRule.waitUntil(120_000L) { model.runState == BatchRunState.FINISHED }
        assertEquals(BatchFileStatus.SKIPPED, model.files.single().status)
        composeRule.runOnUiThread {
            model.startReviewed(context, review, false, ExportMode.FAST_ONLY, true)
        }
        assertFalse(model.isRunning)
        val retryReview = inspect(retryOnly = true)
        assertEquals(1, retryReview.adjustments.size)
        composeRule.runOnUiThread {
            ViewModelStore().apply { put("batch", model) }.clear()
            model = BatchViewModel(context.applicationContext as Application)
        }
        composeRule.waitUntil(30_000L) { !model.isRecovering }
        composeRule.runOnUiThread {
            model.startReviewed(context, retryReview, true, ExportMode.FAST_ONLY, true)
        }
        assertFalse(model.isRunning)
        assertEquals(1, inspect(retryOnly = true).adjustments.size)
    }

    @Test
    fun adjustedBatchIncludesAlreadyFastFilesAndClearlySkipsIneligibleFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extraUris = listOf("quickcut_unequal_tail.mp4", "quickcut_fixture.mp4").map { asset ->
            val file = File(context.cacheDir, "batch_mixed_$asset")
            extraSources += file
            InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            Uri.fromFile(file)
        }
        // The concatenated fixture can report a container duration a few frames
        // below six seconds. Anchor its retained start to its exact 1s keyframe.
        val trailingSeconds = ((MediaReader.metadata(context, extraUris.first()).durationMs -
            1_000L) / 1_000.0).toString()
        composeRule.runOnUiThread {
            model.draftMode = BatchClipMode.KEEP_LAST
            model.draftTrailingSeconds = trailingSeconds
            model.draftMute = true
            model.addVideos(context, extraUris)
        }
        composeRule.waitUntil(30_000L) { !model.isImporting && model.files.size == 3 }
        val review = inspect()
        assertEquals(1, review.fast)
        assertEquals(1, review.adjustments.size)
        assertEquals(2, review.adjustedFast)
        assertEquals(1, review.adjustedSkips)
        composeRule.runOnUiThread {
            model.startReviewed(context, review, false, ExportMode.FAST_ONLY, true)
        }
        composeRule.waitUntil(120_000L) { model.runState == BatchRunState.FINISHED }
        assertEquals(2, model.successes)
        assertEquals(1, model.skips)
        assertEquals(0, model.failures)
        assertTrue(model.files.last().reason!!.contains("짧"))
        assertEquals(trailingSeconds, model.draftTrailingSeconds)
    }

    private fun inspect(retryOnly: Boolean = false): BatchExportOverview = runBlocking {
        withContext(Dispatchers.Main) {
            model.inspectExport(InstrumentationRegistry.getInstrumentation().targetContext, retryOnly)
        }
    }

    private fun openReview() {
        composeRule.onNodeWithText("일괄 편집 및 저장 시작").performClick()
        composeRule.waitUntil(30_000L) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasText("자동 저장 확인"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
