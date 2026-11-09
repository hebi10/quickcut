package com.hebi.quickcut.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.settings.QuickCutThemeMode

data class CutPalette(
    val background: Color,
    val surface: Color,
    val panel: Color,
    val border: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val mutedText: Color,
    val accent: Color,
    val onAccent: Color,
    val accentMuted: Color,
    val secondaryAccent: Color,
    val brandDeep: Color,
    val brandLight: Color,
    val timelineMarker: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
)

object CutPalettes {
    val Light = CutPalette(
        background = Color(0xFFF6F6F4),
        surface = Color(0xFFFFFFFF),
        panel = Color(0xFFECECEA),
        border = Color(0xFFDADAD6),
        primaryText = Color(0xFF17181A),
        secondaryText = Color(0xFF656A70),
        mutedText = Color(0xFF61676D),
        accent = Color(0xFF1C1E20),
        onAccent = Color(0xFFFFFFFF),
        accentMuted = Color(0xFFE3E5E4),
        secondaryAccent = Color(0xFF414549),
        brandDeep = Color(0xFF303338),
        brandLight = Color(0xFF686D72),
        timelineMarker = Color(0xFFB45B3A),
        success = Color(0xFF186B46),
        warning = Color(0xFF8C5714),
        error = Color(0xFFAF2937),
    )
    val Dark = CutPalette(
        background = Color(0xFF111315),
        surface = Color(0xFF1B1D20),
        panel = Color(0xFF26292D),
        border = Color(0xFF363A3E),
        primaryText = Color(0xFFF4F4F2),
        secondaryText = Color(0xFFA7ABB1),
        mutedText = Color(0xFF969DA5),
        accent = Color(0xFFF1F1EF),
        onAccent = Color(0xFF111315),
        accentMuted = Color(0xFF33373B),
        secondaryAccent = Color(0xFFD1D5D8),
        brandDeep = Color(0xFF42474D),
        brandLight = Color(0xFF59626B),
        timelineMarker = Color(0xFFE69B77),
        success = Color(0xFF8DDAAD),
        warning = Color(0xFFF3C76C),
        error = Color(0xFFFF858D),
    )
}

val LocalCutPalette = compositionLocalOf { CutPalettes.Dark }

private fun cutColorScheme(colors: CutPalette, dark: Boolean): ColorScheme {
    val secondaryContainerText = colors.primaryText
    return if (dark) darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentMuted,
        onPrimaryContainer = colors.primaryText,
        secondary = colors.secondaryAccent,
        onSecondary = colors.background,
        secondaryContainer = colors.panel,
        onSecondaryContainer = secondaryContainerText,
        tertiary = colors.timelineMarker,
        onTertiary = colors.background,
        background = colors.background,
        onBackground = colors.primaryText,
        surface = colors.surface,
        onSurface = colors.primaryText,
        onSurfaceVariant = colors.secondaryText,
        surfaceVariant = colors.panel,
        outline = colors.border,
        outlineVariant = colors.border,
        surfaceContainerLowest = colors.background,
        surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.panel,
        surfaceContainerHighest = colors.panel,
        error = colors.error,
        onError = colors.background,
        errorContainer = Color(0xFF3C2428),
        onErrorContainer = Color(0xFFFFDADD),
    ) else lightColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentMuted,
        onPrimaryContainer = colors.primaryText,
        secondary = colors.secondaryAccent,
        onSecondary = colors.surface,
        secondaryContainer = colors.panel,
        onSecondaryContainer = secondaryContainerText,
        tertiary = colors.timelineMarker,
        onTertiary = colors.surface,
        background = colors.background,
        onBackground = colors.primaryText,
        surface = colors.surface,
        onSurface = colors.primaryText,
        onSurfaceVariant = colors.secondaryText,
        surfaceVariant = colors.panel,
        outline = colors.border,
        outlineVariant = colors.border,
        surfaceContainerLowest = colors.background,
        surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.panel,
        surfaceContainerHighest = colors.panel,
        error = colors.error,
        onError = colors.onAccent,
        errorContainer = Color(0xFFFCE5E7),
        onErrorContainer = Color(0xFF781B27),
    )
}

object CutSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object CutSizes {
    val touchTarget = 48.dp
    val appBar = 56.dp
    val primaryButton = 52.dp
    val icon = 24.dp
}

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


private tailrec fun Context.findQuickCutActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findQuickCutActivity()
    else -> null
}

@Composable
fun QuickCutTheme(
    modeOverride: QuickCutThemeMode? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember(context) { QuickCutSettings(context) }
    val savedMode by remember(settings) { settings.observeThemeMode() }
        .collectAsState(initial = settings.loadThemeMode())
    val mode = modeOverride ?: savedMode
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        QuickCutThemeMode.SYSTEM -> systemDark
        QuickCutThemeMode.LIGHT -> false
        QuickCutThemeMode.DARK -> true
    }
    val colors = if (dark) CutPalettes.Dark else CutPalettes.Light
    val activity = context.findQuickCutActivity()
    SideEffect {
        activity?.window?.let { window ->
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = cutColorScheme(colors, dark),
        shapes = cutShapes,
        typography = cutTypography,
    ) {
        CompositionLocalProvider(
            LocalCutPalette provides colors,
            LocalContentColor provides colors.primaryText,
        ) {
            content()
        }
    }
}
