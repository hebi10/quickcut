package com.hebi.quickcut.batch

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.media.ExportMethod
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.GalleryWriter
import com.hebi.quickcut.settings.BatchDraftSettings
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.ui.QuickCutTheme
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExactBatchExportInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var model: BatchViewModel
    private lateinit var source: File
    private lateinit var settings: QuickCutSettings
    private lateinit var previousDraft: BatchDraftSettings
    private lateinit var previousDate: SaveDateMode
    private lateinit var sessionStore: BatchSessionStore
    private var previousSession: BatchSessionSnapshot? = null
    private val generatedOutputs = mutableListOf<Uri>()
    private val extraSources = mutableListOf<File>()

    @Before
    fun importThreeSecondCutBetweenKeyframes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
            assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        }
        settings = QuickCutSettings(context)
        previousDraft = settings.loadBatch()
        previousDate = settings.loadSaveDate()
        sessionStore = BatchSessionStore(context)
        previousSession = sessionStore.load()
        sessionStore.clear()
        source = File(context.cacheDir, "quickcut_batch_exact_3s.mp4")
        instrumentation.context.assets.open("quickcut_keyframes.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        composeRule.runOnUiThread {
            model = BatchViewModel(context.applicationContext as Application)
            model.draftName = "앞 3초 정확히 삭제"
            model.draftMode = BatchClipMode.REMOVE_EDGES
            model.draftLeadingSeconds = "3"
            model.draftTrailingSeconds = "0"
            model.draftMute = false
            model.saveDateMode = SaveDateMode.CURRENT
            model.addVideos(context, listOf(Uri.fromFile(source)))
        }
        composeRule.waitUntil(30_000L) { !model.isImporting && model.files.size == 1 }
        assertTrue("The fixture must retain more than the next keyframe interval",
            model.files.single().durationMs >= 7_900L)
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
            generatedOutputs.forEach { output ->
                runCatching { context.contentResolver.delete(output, null, null) }
            }
            composeRule.runOnUiThread {
                ViewModelStore().apply { put("batch-exact", model) }.clear()
            }
        }
        if (::sessionStore.isInitialized) {
            previousSession?.let(sessionStore::save) ?: sessionStore.clear()
        }
        if (::settings.isInitialized) {
            settings.saveBatch(previousDraft)
            settings.saveSaveDate(previousDate)
        }
        if (::source.isInitialized) source.delete()
        extraSources.forEach(File::delete)
    }

    @Test
    fun refusedFastSaveRequiresReviewedExactConsentAndRetriesOriginalInterval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousAction = settings.loadOriginalAction()
        val exactSource = File.createTempFile("quickcut_batch_exact_boundary_", ".mp4", context.cacheDir)
        extraSources += exactSource
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_exact_boundary.mp4")
            .use { input -> exactSource.outputStream().use { input.copyTo(it) } }
        val sourceBytes = exactSource.readBytes()
        // Include pending rows: inspection/dismissal must not publish or leave a new output.
        fun outputIds(): Set<Long> {
            val prefix = exactSource.nameWithoutExtension + "_QuickCut_"
            return context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
                null, null, null)!!.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1).startsWith(prefix)) add(cursor.getLong(0))
                    }
                }
            }
        }
        try {
            composeRule.runOnUiThread { model.removeVideo(model.files.single().uri) }
            composeRule.waitUntil(30_000L) { !model.isImporting && model.files.isEmpty() }
            composeRule.runOnUiThread { model.addVideos(context, listOf(Uri.fromFile(exactSource))) }
            composeRule.waitUntil(30_000L) { !model.isImporting && model.files.size == 1 }
            assertEquals(Uri.fromFile(exactSource).toString(), model.files.single().uri)
            assertTrue(model.files.single().hasAudio)
            assertRed(centerColor(context, Uri.fromFile(exactSource), 1_950_000L),
                "Exact-boundary fixture must be red before the next keyframe")
            assertBlue(centerColor(context, Uri.fromFile(exactSource), 2_000_000L),
                "Exact-boundary fixture must switch to blue at the next keyframe")
            composeRule.runOnUiThread {
                model.draftLeadingSeconds = "1.95"
                model.draftTrailingSeconds = "0"
                model.originalAction = OriginalAction.KEEP
            }
            val requested = (model.previewTemplate()!!.plan(model.files.single().uri,
                model.files.single().durationMs, model.files.single().hasAudio) as BatchPlan.Ready).timeline
            assertEquals(1_950_000L, requested.clips.single().startUs)
            val before = outputIds()
            // Same real native boundary used by ExactTrimInstrumentedTest's refusal regression.
            composeRule.runOnUiThread { model.start(context, exportMode = ExportMode.FAST_ONLY) }
            composeRule.waitUntil(120_000L) { model.runState == BatchRunState.FINISHED }
            val refused = model.files.single()
            assertEquals(BatchFileStatus.SKIPPED, refused.status)
            assertNotNull(refused.reencodeRefusal)
            assertTrue(refused.reencodeRefusal!!.matches(requested, SaveDateMode.CURRENT.name,
                refused.originalDate?.epochMs))
            assertEquals(refused.reencodeRefusal, sessionStore.load()!!.files.single().reencodeRefusal)
            assertNull(refused.savedOutputUri)
            assertNull(refused.exportReport)
            assertEquals(before, outputIds())
            assertTrue(model.originalCandidates.isEmpty())

            val dismissed = runBlocking { withContext(Dispatchers.Main) { model.inspectExport(context, true) } }
            val file = dismissed.reviewedFiles.single()
            assertTrue(file.reencodeRequired)
            assertFalse(file.fastAvailable)
            assertFalse(file.canRecommend)
            assertEquals(0, dismissed.adjustedFast)
            assertEquals(requested, file.requested)
            assertEquals(BatchExportChoice.EXACT, BatchExportChoicePolicy.defaults(listOf(file))[file.uri])
            assertNull(BatchExportChoicePolicy.resolve(listOf(file), mapOf(file.uri to BatchExportChoice.RECOMMENDED)))
            // Inspection itself (including dismissing this overview without submission) has no side effects.
            assertEquals(before, outputIds())
            assertEquals(BatchFileStatus.SKIPPED, model.files.single().status)
            // The reviewed aggregate fast-only shortcut must exclude the known refusal too.
            composeRule.runOnUiThread {
                model.startReviewed(context, dismissed, true, ExportMode.FAST_ONLY)
            }
            composeRule.waitUntil(120_000L) { model.runState == BatchRunState.FINISHED }
            assertEquals(BatchFileStatus.SKIPPED, model.files.single().status)
            assertEquals(refused.reencodeRefusal, model.files.single().reencodeRefusal)
            assertEquals(before, outputIds())
            composeRule.runOnUiThread { model.start(context, retryOnly = true, exportMode = ExportMode.ALLOW_REENCODE) }
            composeRule.waitUntil(120_000L) { model.runState == BatchRunState.FINISHED }
            assertEquals(BatchFileStatus.SKIPPED, model.files.single().status)
            assertEquals(refused.reencodeRefusal, model.files.single().reencodeRefusal)
            assertEquals(before, outputIds())

            val consented = runBlocking { withContext(Dispatchers.Main) { model.inspectExport(context, true) } }
            composeRule.runOnUiThread {
                model.startReviewed(context, consented, true,
                    mapOf(file.uri to BatchExportChoice.EXACT))
            }
            composeRule.waitUntil(120_000L) { model.runState == BatchRunState.FINISHED }
            val saved = model.files.single()
            assertEquals(BatchFileStatus.SAVED, saved.status)
            assertNull(saved.reencodeRefusal)
            assertNull(sessionStore.load()!!.files.single().reencodeRefusal)
            assertNotNull(saved.exportReport)
            assertEquals(requested.totalDurationMs, saved.outputExpectedDurationMs)
            val output = Uri.parse(saved.savedOutputUri!!)
            generatedOutputs += output
            assertRed(centerColor(context, output, 0L),
                "The first saved frame must retain red footage before the next keyframe")
            val actual = MediaReader.validateExport(context, output, requested.totalDurationMs, true)
            assertTrue("Duration must preserve the requested interval within one 30fps frame: " +
                "requested=${requested.totalDurationMs}ms actual=${actual.durationMs}ms",
                abs(actual.durationMs - requested.totalDurationMs) <= 34L)
            assertEquals(before.size + 1, outputIds().size)
            assertTrue(exactSource.exists())
            assertTrue(sourceBytes.contentEquals(exactSource.readBytes()))
            assertTrue(model.originalCandidates.isEmpty())
        } finally {
            composeRule.runOnUiThread { model.originalAction = previousAction }
            settings.saveOriginalAction(previousAction)
        }
    }

    @Test
    fun automaticBatchSavesPartialAndFullEncodingFilesWithoutSkipping() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val additional = File(context.cacheDir, "quickcut_batch_short_gop.mp4")
        extraSources += additional
        instrumentation.context.assets.open("quickcut_fixture.mp4").use { input ->
            additional.outputStream().use { input.copyTo(it) }
        }
        composeRule.runOnUiThread {
            model.draftLeadingSeconds = "0:0.5"
            model.draftTrailingSeconds = "0:0.5"
            model.addVideos(context, listOf(Uri.fromFile(additional)))
        }
        composeRule.waitUntil(30_000L) { !model.isImporting && model.files.size == 2 }
        val review = runBlocking { withContext(Dispatchers.Main) { model.inspectExport(context, false) } }
        assertEquals(2, review.ready)
        composeRule.runOnUiThread { model.startReviewed(context, review, false) }
        composeRule.waitUntil(120_000L) { model.runState == BatchRunState.FINISHED }
        assertEquals(2, model.successes)
        assertEquals(0, model.failures)
        assertEquals(0, model.skips)
        assertEquals(ExportMethod.PARTIAL_REENCODE, model.files.first().exportReport?.method)
        assertEquals(ExportMethod.VIDEO_REENCODE, model.files.last().exportReport?.method)
        model.files.forEach { row ->
            assertEquals(row.durationMs - 1_000L, row.outputExpectedDurationMs)
            val actual = MediaReader.metadata(context, Uri.parse(row.savedOutputUri!!))
            assertTrue(abs(actual.durationMs - row.outputExpectedDurationMs!!) <= 100L)
            assertTrue(actual.hasAudio)
        }
    }

    @Test
    fun exactAutomaticSaveKeepsThreeSecondCutAndReportsCompletedMethod() {
        showScreen()
        composeRule.onNodeWithText("일괄 편집 및 저장 시작").performClick()
        composeRule.waitUntil(30_000L) {
            composeRule.onAllNodes(hasText("자동 저장 확인")).fetchSemanticsNodes().isNotEmpty()
        }
        // This source has sync frames at 2s and 4s. Exact automatic save must
        // preserve the requested 3s boundary instead of choosing the 4s shortcut.
        composeRule.onNodeWithText("고속 저장 가능한 영상만").assertDoesNotExist()
        composeRule.onNodeWithText("위치 조정 후 고속 저장").assertDoesNotExist()
        composeRule.onNodeWithTag("batch-review-list").performScrollToNode(hasText("전체 자동 저장"))
        composeRule.onNodeWithText("전체 자동 저장").assertIsEnabled().performClick()
        composeRule.waitUntil(120_000L) { model.runState == BatchRunState.FINISHED }

        val row = model.files.single()
        assertEquals("A valid non-keyframe cut must be saved", BatchFileStatus.SAVED, row.status)
        val requestedDuration = row.durationMs - 3_000L
        assertEquals(requestedDuration, row.outputExpectedDurationMs)
        assertEquals("3", model.draftLeadingSeconds)
        assertEquals(3_000L, model.previewTemplate()!!.leadingMs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actual = MediaReader.metadata(context, Uri.parse(row.savedOutputUri!!))
        assertTrue("Output must preserve the 3s cut, not move to the 4s keyframe",
            abs(actual.durationMs - requestedDuration) <= 250L)
        assertTrue("The source audio must remain present", actual.hasAudio)
        assertEquals(requestedDuration,
            sessionStore.load()!!.files.single().outputExpectedDurationMs)
        assertFileText(formatTime(row.durationMs) + " → " + formatTime(requestedDuration))
        assertFileText("처리 방식:", substring = true)
        assertNotNull("A successful export must report its actual method", row.exportReport)
        assertEquals(row.exportReport, sessionStore.load()!!.files.single().exportReport)
        composeRule.runOnUiThread {
            ViewModelStore().apply { put("batch-exact", model) }.clear()
            model = BatchViewModel(context.applicationContext as Application)
        }
        composeRule.waitUntil(30_000L) { !model.isRecovering }
        assertEquals(BatchFileStatus.SAVED, model.files.single().status)
        assertEquals(row.savedOutputUri, model.files.single().savedOutputUri)
        assertEquals(row.exportReport, model.files.single().exportReport)
        assertEquals("3", model.draftLeadingSeconds)
    }

    @Test
    fun savedVersionOneJournalWithoutReportRemainsSavedAndDisplaysUnknown() {
        restorePublishedJournal { it.remove("exportReport") }
        assertNull(model.files.single().exportReport)
        showScreen()
        assertFileText("처리 방식: 확인할 수 없음")
    }

    @Test
    fun futureReportMethodDoesNotDiscardAValidSavedJournalOrClaimFastCopy() {
        restorePublishedJournal {
            it.put("exportReport", JSONObject().apply {
                put("method", "FUTURE_UNRECOGNIZED_METHOD")
                put("elapsedMs", 123L)
            })
        }
        assertEquals(ExportMethod.UNKNOWN, model.files.single().exportReport?.method)
        assertEquals(123L, model.files.single().exportReport?.elapsedMs)
        showScreen()
        assertFileText("처리 방식: 확인할 수 없음", substring = true)
    }

    @Test
    fun restoredFullEncodingShowsBothReasonsInTheFileResult() {
        restorePublishedJournal {
            it.put("exportReport", JSONObject().apply {
                put("method", "VIDEO_REENCODE")
                put("elapsedMs", 123L)
                put("copyReason", "KEYFRAME")
                put("smartCutReason", "FRAME_RATE")
            })
        }
        showScreen()
        assertFileText("원본 복사 제한: 자르기 시작 위치가 키프레임", substring = true)
        assertFileText("고속 부분 처리 제한: 프레임 간격이 일정하지 않거나", substring = true)
        sessionStore.save(BatchSessionSnapshot(model.files, model.previewTemplate(),
            SaveDateMode.CURRENT, BatchRunState.FINISHED))
        assertEquals(model.files.single().exportReport,
            sessionStore.load()?.files?.single()?.exportReport)
    }

    @Test
    fun unknownFutureReasonDoesNotDiscardSavedResultOrInventACondition() {
        restorePublishedJournal {
            it.put("exportReport", JSONObject().apply {
                put("method", "VIDEO_REENCODE")
                put("elapsedMs", 123L)
                put("smartCutReason", "FUTURE_REASON")
            })
        }
        assertEquals(ExportMethod.VIDEO_REENCODE, model.files.single().exportReport?.method)
        showScreen()
        assertFileText("고속 부분 처리 제한: 원인 확인 불가", substring = true)
    }

    private fun restorePublishedJournal(changeReport: (JSONObject) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = runBlocking { GalleryWriter(context).save(source) }
        generatedOutputs += output
        val saved = model.files.single().copy(
            status = BatchFileStatus.SAVED,
            savedOutputUri = output.toString(),
            outputExpectedDurationMs = model.files.single().durationMs,
            outputExpectedAudio = true,
        )
        val template = model.previewTemplate()
        composeRule.runOnUiThread {
            ViewModelStore().apply { put("batch-exact", model) }.clear()
        }
        sessionStore.save(BatchSessionSnapshot(listOf(saved), template,
            SaveDateMode.CURRENT, BatchRunState.FINISHED))
        // Exercise the actual schema-1 file that older installations leave behind.
        val journal = File(context.filesDir, "quickcut_batch_session_v1.json")
        val legacy = JSONObject(journal.readText())
        changeReport(legacy.getJSONArray("files").getJSONObject(0))
        journal.writeText(legacy.toString())
        composeRule.runOnUiThread {
            model = BatchViewModel(context.applicationContext as Application)
        }
        composeRule.waitUntil(30_000L) { !model.isRecovering }
        assertEquals(BatchFileStatus.SAVED, model.files.single().status)
        assertEquals(output.toString(), model.files.single().savedOutputUri)
        assertEquals(saved.outputExpectedDurationMs, model.files.single().outputExpectedDurationMs)
    }

    private fun centerColor(context: Context, uri: Uri, timeUs: Long): Int {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getScaledFrameAtTime(timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST, 160, 90)
                ?: throw AssertionError("The requested frame at ${timeUs}us must decode")
            return try {
                frame.getPixel(frame.width / 2, frame.height / 2)
            } finally { frame.recycle() }
        } finally { retriever.release() }
    }

    private fun assertRed(color: Int, message: String) {
        assertTrue("$message: red=${Color.red(color)} blue=${Color.blue(color)}",
            Color.red(color) > 150 && Color.red(color) > Color.blue(color) + 100)
    }

    private fun assertBlue(color: Int, message: String) {
        assertTrue("$message: red=${Color.red(color)} blue=${Color.blue(color)}",
            Color.blue(color) > 150 && Color.blue(color) > Color.red(color) + 100)
    }

    private fun showScreen() {
        composeRule.setContent {
            QuickCutTheme { BatchScreen(model, {}, {}, { _, _, _ -> }) }
        }
    }

    private fun assertFileText(text: String, substring: Boolean = false) {
        val matcher = hasText(text, substring = substring)
        // Rows below the taller duration editor are not composed until scrolled
        // into the LazyColumn viewport. Scroll the list before querying a row.
        composeRule.onNodeWithTag("batch-file-list").performScrollToNode(matcher)
        composeRule.onNode(matcher).assertExists()
    }
}
