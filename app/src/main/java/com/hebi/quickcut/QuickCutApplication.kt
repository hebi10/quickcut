package com.hebi.quickcut

import android.app.Application
import com.hebi.quickcut.batch.BatchViewModel
import com.hebi.quickcut.editor.EditorViewModel

class QuickCutApplication : Application() {
    val editorModel by lazy { EditorViewModel(this) }
    val batchModel by lazy { BatchViewModel(this) }
}
