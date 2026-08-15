package com.cj2026.planner.ui

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import com.cj2026.planner.R

private val DiffAdded = Color(0xFF00C853)
private val DiffRemoved = Color(0xFFFF5F38)
private val Skill = Color(0xFFCC7D5E)

private val UiFont = FontFamily(
    Font(R.font.noto_serif_sc_regular, FontWeight.Normal),
    Font(R.font.noto_serif_sc_semibold, FontWeight.SemiBold)
)
val CodeFont = FontFamily(Font(R.font.jetbrains_mono_nfm_regular, FontWeight.Normal))

private val LightColors = lightColorScheme(
    primary = Color(0xFFDA7756),
    onPrimary = Color.White,
    secondary = Color(0xFF70685E),
    onSecondary = Color.White,
    secondaryContainer = DiffAdded.copy(alpha = 0.16f),
    onSecondaryContainer = Color(0xFF141413),
    tertiary = Skill,
    onTertiary = Color.White,
    error = DiffRemoved,
    onError = Color.White,
    background = Color(0xFFF5F4EE),
    onBackground = Color(0xFF141413),
    surface = Color(0xFFF5F4EE),
    onSurface = Color(0xFF141413),
    surfaceVariant = Color(0xFFE8E4D9),
    onSurfaceVariant = Color(0xFF5E5A52),
    outline = Color(0xFFC9C3B7),
    outlineVariant = Color(0xFFDDD8CD),
    inverseSurface = Color(0xFF262624),
    inverseOnSurface = Color(0xFFF5F4EE),
    inversePrimary = Color(0xFFCC785C)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCC785C),
    onPrimary = Color(0xFF141413),
    secondary = Color(0xFFC8C0B5),
    onSecondary = Color(0xFF262624),
    secondaryContainer = DiffAdded.copy(alpha = 0.24f),
    onSecondaryContainer = Color(0xFFF5F4EE),
    tertiary = Skill,
    onTertiary = Color(0xFF141413),
    error = DiffRemoved,
    onError = Color(0xFF141413),
    background = Color(0xFF262624),
    onBackground = Color(0xFFF5F4EE),
    surface = Color(0xFF262624),
    onSurface = Color(0xFFF5F4EE),
    surfaceVariant = Color(0xFF363530),
    onSurfaceVariant = Color(0xFFD3CDC2),
    outline = Color(0xFF797267),
    outlineVariant = Color(0xFF4A4841),
    inverseSurface = Color(0xFFF5F4EE),
    inverseOnSurface = Color(0xFF141413),
    inversePrimary = Color(0xFFDA7756)
)

private val AppTypography = Typography().run {
    val uiStyle: TextStyle.() -> TextStyle = { copy(fontFamily = UiFont) }
    copy(
        displayLarge = displayLarge.uiStyle(),
        displayMedium = displayMedium.uiStyle(),
        displaySmall = displaySmall.uiStyle(),
        headlineLarge = headlineLarge.uiStyle(),
        headlineMedium = headlineMedium.uiStyle(),
        headlineSmall = headlineSmall.uiStyle(),
        titleLarge = titleLarge.uiStyle(),
        titleMedium = titleMedium.uiStyle(),
        titleSmall = titleSmall.uiStyle(),
        bodyLarge = bodyLarge.uiStyle(),
        bodyMedium = bodyMedium.uiStyle(),
        bodySmall = bodySmall.uiStyle(),
        labelLarge = labelLarge.uiStyle(),
        labelMedium = labelMedium.uiStyle(),
        labelSmall = labelSmall.uiStyle()
    )
}

@Composable
fun ChinaJoyTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colors: ColorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = colors.surface.toArgb()
        window.navigationBarColor = colors.surface.toArgb()

        var flags = window.decorView.systemUiVisibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = if (darkTheme) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = if (darkTheme) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        window.decorView.systemUiVisibility = flags
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content
    )
}
