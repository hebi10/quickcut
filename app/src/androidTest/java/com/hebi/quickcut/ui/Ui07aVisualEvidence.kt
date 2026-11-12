package com.hebi.quickcut.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Screenshot proof from the active Android window, including actual video surfaces and dialogs.
 * Keeps UI-07-A inside existing test methods so API 29's 292-case contract is unchanged.
 */
internal object Ui07aVisualEvidence {
    fun capture(name: String) {
        require(name.matches(Regex("ui07a_(light|dark)_[a-z_]+\\.png"))) {
            "Invalid UI-07-A screenshot name: $name"
        }
        val instrument = InstrumentationRegistry.getInstrumentation()
        val screenshot = checkNotNull(instrument.uiAutomation.takeScreenshot()) {
            "Android window screenshot unavailable: $name"
        }
        try {
            check(screenshot.width >= 200 && screenshot.height >= 200) {
                "Invalid device screenshot geometry: $name"
            }
            val samples = HashSet<Int>()
            for (row in 1..48) for (column in 1..32) {
                val x = screenshot.width * column / 33
                val y = screenshot.height * row / 49
                samples.add(screenshot.getPixel(x, y) and 0x00FFFFFF)
            }
            check(samples.size >= 3) {
                "Blank/flat Android UI screenshot: $name (${samples.size} sampled colors)"
            }
            val dir = File(instrument.targetContext.filesDir, "design-review")
            check(dir.isDirectory || dir.mkdirs())
            val target = File(dir, name)
            target.outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            check(target.length() >= 512L) { "Empty PNG capture: $name" }
        } finally {
            screenshot.recycle()
        }
    }
}

/** The real app uses this viewport wrapper in QuickCutApp. Standalone
 * screen test screenshots must not overlap the Android status/navigation bars.
 */
@Composable
internal fun Ui07aAppSafeSurface(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(LocalCutPalette.current.background)
            .safeDrawingPadding()
    ) {
        content()
    }
}
