package com.hebi.quickcut.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object CutColors {
    val background = Color(0xFF0B0810)
    val surface = Color(0xFF191324)
    val panel = Color(0xFF292039)
    val border = Color(0xFF4B3C61)
    val primaryText = Color(0xFFF8F6FF)
    val secondaryText = Color(0xFFCFC6DF)
    val mutedText = Color(0xFFA79AB9)
    val accent = Color(0xFF8B5CF6)
    val onAccent = Color(0xFF0B0810)
    val accentMuted = Color(0xFF382252)
    val secondaryAccent = Color(0xFFC4B5FD)
    val brandDeep = Color(0xFF6D28D9)
    val brandLight = Color(0xFFA78BFA)
    val success = Color(0xFF22C55E)
}

private val colorScheme = darkColorScheme(
    primary = CutColors.accent,
    onPrimary = CutColors.onAccent,
    primaryContainer = CutColors.accentMuted,
    onPrimaryContainer = CutColors.secondaryAccent,
    secondary = CutColors.secondaryAccent,
    onSecondary = CutColors.background,
    secondaryContainer = CutColors.panel,
    onSecondaryContainer = CutColors.secondaryAccent,
    tertiary = CutColors.accent,
    onTertiary = CutColors.onAccent,
    background = CutColors.background,
    onBackground = CutColors.primaryText,
    surface = CutColors.surface,
    onSurface = CutColors.primaryText,
    onSurfaceVariant = CutColors.secondaryText,
    surfaceVariant = CutColors.panel,
    outline = CutColors.border,
    outlineVariant = CutColors.border.copy(alpha = 0.6f),
    surfaceContainerLowest = CutColors.background,
    surfaceContainerLow = CutColors.surface,
    surfaceContainer = CutColors.surface,
    surfaceContainerHigh = CutColors.panel,
    surfaceContainerHighest = CutColors.panel,
)

val CutShape = RoundedCornerShape(14.dp)
val CutPanelShape = RoundedCornerShape(20.dp)
val CutDialogShape = RoundedCornerShape(16.dp)
val CutSmallShape = RoundedCornerShape(10.dp)

private val cutShapes = Shapes(
    extraSmall = CutSmallShape,
    small = CutShape,
    medium = CutShape,
    large = CutPanelShape,
    extraLarge = RoundedCornerShape(24.dp),
)

private val cutTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 31.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 29.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

@Composable
fun QuickCutTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, shapes = cutShapes, typography = cutTypography) {
        CompositionLocalProvider(LocalContentColor provides CutColors.primaryText) {
            content()
        }
    }
}

