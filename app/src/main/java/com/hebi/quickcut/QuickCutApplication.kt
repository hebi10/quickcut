package com.hebi.quickcut

import android.app.Application
import com.hebi.quickcut.batch.BatchViewModel
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.media.ExportStagingFiles

class QuickCutApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { ExportStagingFiles.cleanup(cacheDir) }
    }

    val editorModel by lazy { EditorViewModel(this) }
    val batchModel by lazy { BatchViewModel(this) }
}
