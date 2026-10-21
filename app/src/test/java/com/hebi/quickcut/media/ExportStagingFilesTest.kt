package com.hebi.quickcut.media

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportStagingFilesTest {
    @Test fun removesInterruptedOwnedOutputsBeforeAnotherExport() = withDirectory { directory ->
        val stale = File(directory, "quickcut_12345678-1234-1234-1234-123456789abc.mp4").apply { writeText("partial") }
        ExportStagingFiles.cleanup(directory)
        assertFalse(stale.exists())
    }

    @Test fun removesLegacyNumericStagingFiles() = withDirectory { directory ->
        val stale = File(directory, "quickcut_1234567890123.mp4").apply { writeText("partial") }
        ExportStagingFiles.cleanup(directory)
        assertFalse(stale.exists())
    }

    @Test fun removesNativeFirstPassLeftByProcessDeath() = withDirectory { directory ->
        val stale = File(directory, "quickcut_12345678-1234-1234-1234-123456789abc.mp4.smartcut.mp4")
            .apply { writeText("native first pass") }
        ExportStagingFiles.cleanup(directory)
        assertFalse(stale.exists())
    }

    @Test fun removesDatedCopyLeftByProcessDeath() = withDirectory { directory ->
        val stale = File(directory, "quickcut_date_12345678-1234-1234-1234-123456789abc.mp4")
            .apply { writeText("dated copy") }
        ExportStagingFiles.cleanup(directory)
        assertFalse(stale.exists())
    }

    @Test fun leavesSourceFixturesAndUnrelatedCacheUntouched() = withDirectory { directory ->
        val files = listOf(
            "quickcut_fixture.mp4", "original.mp4", "quickcut_user_video.mp4",
            "quickcut_user_video.mp4.smartcut.mp4", "original.mp4.smartcut.mp4",
            "quickcut_date_original.mp4", "quickcut_date_1234.mp4",
            "quickcut_date_12345678-1234-1234-1234-123456789abc.mp4.backup",
            "quickcut_12345678-1234-1234-1234-123456789abc.mp4.smartcut.mp4.backup",
        )
            .map { File(directory, it).apply { writeText("source") } }
        ExportStagingFiles.cleanup(directory)
        assertTrue(files.all(File::exists))
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("quickcut-staging-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}

