package com.hebi.quickcut.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object CutColors {
    val background = Color(0xFF0B0C0E)
    val surface = Color(0xFF151619)
    val panel = Color(0xFF202126)
    val border = Color(0xFF36383E)
    val primaryText = Color(0xFFF4F4F5)
    val secondaryText = Color(0xFFB7BAC0)
    val mutedText = Color(0xFF91959D)
    val accent = Color(0xFF3F8BFF)
}

private val colorScheme = darkColorScheme(
    primary = CutColors.accent,
    onPrimary = Color.White,
    background = CutColors.background,
    onBackground = CutColors.primaryText,
    surface = CutColors.surface,
    onSurface = CutColors.primaryText,
    onSurfaceVariant = CutColors.secondaryText,
    surfaceVariant = CutColors.panel,
    outline = CutColors.border,
)

val CutShape = RoundedCornerShape(4.dp)

private val cutShapes = Shapes(
    extraSmall = CutShape,
    small = CutShape,
    medium = CutShape,
    large = CutShape,
    extraLarge = CutShape,
)

@Composable
fun QuickCutTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, shapes = cutShapes) {
        CompositionLocalProvider(LocalContentColor provides CutColors.primaryText) {
            content()
        }
    }
}

