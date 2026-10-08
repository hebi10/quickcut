package com.hebi.quickcut.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.hebi.quickcut.media.MediaReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ClipThumbnail(uri: String, timeMs: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var frame by remember(uri, timeMs) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri, timeMs) {
        val bitmap = withContext(Dispatchers.IO) {
            MediaReader.thumbnail(context.applicationContext, Uri.parse(uri), timeMs)
        }
        frame = bitmap?.asImageBitmap()
    }
    Box(modifier = modifier.background(CutColors.panel), contentAlignment = Alignment.Center) {
        val image = frame
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Outlined.VideoLibrary, contentDescription = null, tint = CutColors.mutedText)
        }
    }
}
