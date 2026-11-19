package com.hebi.quickcut.batch

internal object BatchLayoutPolicy {
    fun singleScroll(heightDp: Float, fontScale: Float): Boolean =
        heightDp < 580f || (fontScale >= 1.6f && heightDp < 820f)
}
