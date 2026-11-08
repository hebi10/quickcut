package com.hebi.quickcut.ui

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import com.hebi.quickcut.media.SavedVideoTechnicalInfo
import com.hebi.quickcut.media.SavedVideoTechnicalReader
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CompletedExportFacts(uri: String) {
    val context = LocalContext.current
    val data by produceState<SavedVideoTechnicalInfo?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { SavedVideoTechnicalReader.read(context.applicationContext, Uri.parse(uri)) }
                .getOrNull()
        }
    }
    val details = data ?: return
    val fps = details.frameRate?.let {
        " · " + String.format(Locale.US, "%.1f FPS", it)
    }.orEmpty()
    val size = details.fileBytes?.let {
        " · " + String.format(Locale.US, "%.1f MB", it / 1_048_576.0)
    }.orEmpty()
    Text(
        "실제 출력: ${details.width}×${details.height}$fps$size",
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = CutColors.secondaryText,
    )
}
