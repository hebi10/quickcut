package com.hebi.quickcut.media

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.OriginalAction
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OriginalMediaActionsInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aSourceOutsideMediaStoreIsNeverDeleted() {
        val file = File(context.cacheDir, "quickcut-local-original.mp4")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            assertThrows(UnsupportedOperationException::class.java) {
                OriginalMediaActions.prepareRequest(
                    context = context,
                    originalSources = listOf(Uri.fromFile(file).toString()),
                    savedOutput = Uri.parse("content://media/external/video/media/90000"),
                    action = OriginalAction.DELETE,
                )
            }
            assertTrue("Unsupported original must remain intact", file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun keepingAnOriginalCannotGenerateAnySystemDeletionRequest() {
        assertThrows(IllegalArgumentException::class.java) {
            OriginalMediaActions.prepareRequest(
                context = context,
                originalSources = listOf("content://media/external/video/media/11"),
                savedOutput = Uri.parse("content://media/external/video/media/12"),
                action = OriginalAction.KEEP,
            )
        }
    }

    @Test
    fun preparingRequestsRequiresAndroidUserConfirmationAndDoesNotDelete() = runBlocking {
        val source = File(context.cacheDir, "quickcut-fixture-to-register.mp4")
        val sourceStream = InstrumentationRegistry.getInstrumentation().context.assets
            .open("quickcut_fixture.mp4")
        sourceStream.use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val sourceUri = GalleryWriter(context).save(source)
        try {
            val outputUri = Uri.parse("content://media/external/video/media/987654321")
            for (action in listOf(OriginalAction.TRASH, OriginalAction.DELETE)) {
                val request = OriginalMediaActions.prepareRequest(
                    context = context,
                    originalSources = listOf(sourceUri.toString(), sourceUri.toString()),
                    savedOutput = outputUri,
                    action = action,
                )
                assertNotNull("System confirmation is required", request.intentSender)
                assertTrue(
                    "Creating an OS confirmation must not delete the source",
                    MediaReader.metadata(context, sourceUri).durationMs > 0L,
                )
            }
        } finally {
            context.contentResolver.delete(sourceUri, null, null)
            source.delete()
        }
    }
}
