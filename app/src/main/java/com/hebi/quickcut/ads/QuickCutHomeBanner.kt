package com.hebi.quickcut.ads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.hebi.quickcut.R
import com.hebi.quickcut.ui.LocalCutPalette

/**
 * Only the home tab owns this view. Disposing it on navigation prevents requests
 * during editing, saving, original deletion and restore confirmation.
 */
@Composable
internal fun QuickCutHomeBanner() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val palette = LocalCutPalette.current
    val unitId = stringResource(R.string.quickcut_admob_banner_unit_id)

    BoxWithConstraints(Modifier.fillMaxWidth().testTag("quickcut-home-ad-slot")) {
        val widthDp = maxWidth.value.toInt()
        if (widthDp < 320) return@BoxWithConstraints

        val adSize = remember(context, widthDp) {
            AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp)
        }
        val adView = remember(context, widthDp, unitId) {
            AdView(context).apply {
                adUnitId = unitId
                setAdSize(adSize)
            }
        }
        var unavailable by remember(adView) { mutableStateOf(false) }

        DisposableEffect(adView, owner) {
            adView.adListener = object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    unavailable = true
                }
            }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> adView.resume()
                    Lifecycle.Event.ON_PAUSE -> adView.pause()
                    else -> Unit
                }
            }
            owner.lifecycle.addObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                adView.resume()
            }
            onDispose {
                owner.lifecycle.removeObserver(observer)
                adView.destroy()
            }
        }
        LaunchedEffect(adView) { adView.loadAd(AdRequest.Builder().build()) }

        if (!unavailable) {
            Column(
                Modifier.fillMaxWidth().wrapContentSize(Alignment.Center)
                    .testTag("quickcut-home-ad-banner"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("광고", style = MaterialTheme.typography.labelSmall,
                    color = palette.mutedText)
                key(adView) {
                    AndroidView(
                        factory = { adView },
                        modifier = Modifier.fillMaxWidth().height(adSize.height.dp),
                    )
                }
            }
        }
    }
}
