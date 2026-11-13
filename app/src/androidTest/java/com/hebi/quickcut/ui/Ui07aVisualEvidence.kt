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
        var accepted: Bitmap? = null
        var sampledColors = 0
        val automation = instrument.uiAutomation
        for (attempt in 0 until 3) {
            Thread.sleep(if (attempt == 0) 450L else 300L)
            val shot = automation.takeScreenshot() ?: continue
            if (shot.width < 200 || shot.height < 200) {
                shot.recycle()
                continue
            }
            val samples = HashSet<Int>()
            // Exclude status/navigation bars: their pixels can make a completely
            // blank application frame appear to have multiple colors.
            for (row in 9..42) for (column in 4..28) {
                samples.add(shot.getPixel(
                    shot.width * column / 32,
                    shot.height * row / 50,
                ) and 0x00FFFFFF)
            }
            sampledColors = samples.size
            if (sampledColors >= 3) {
                accepted = shot
                break
            }
            shot.recycle()
        }
        val screenshot = checkNotNull(accepted) {
            "Blank Android UI screenshot: $name ($sampledColors content colors)"
        }
        try {
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
