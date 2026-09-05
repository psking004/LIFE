package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val LifeLightColorScheme = lightColorScheme(
    primary = GbPrimary,
    onPrimary = GbOnPrimary,
    primaryContainer = GbPrimaryContainer,
    onPrimaryContainer = GbOnPrimaryContainer,
    secondary = GbAccentPurple,
    onSecondary = Color.White,
    secondaryContainer = GbSurfaceNavLight,
    onSecondaryContainer = GbTextPrimaryLight,
    tertiary = LifeRefinement,
    onTertiary = Color.White,
    background = GbBgLight,
    onBackground = GbTextPrimaryLight,
    surface = GbSurfaceLight,
    onSurface = GbTextPrimaryLight,
    surfaceVariant = GbSurfaceVariantLight,
    onSurfaceVariant = GbTextSecondaryLight,
    outline = GbBorderLight,
    outlineVariant = GbBorderLight.copy(alpha = 0.5f),
    error = LifeProblem,
    onError = Color.White,
    errorContainer = LifeProblemBg,
    onErrorContainer = LifeProblem
)

private val LifeDarkColorScheme = darkColorScheme(
    primary = GbPrimaryDark,
    onPrimary = GbOnPrimaryContainer,
    primaryContainer = GbPrimaryDark.copy(alpha = 0.2f),
    onPrimaryContainer = GbPrimaryContainer,
    secondary = GbPrimaryDark,
    onSecondary = Color.Black,
    secondaryContainer = GbSurfaceVariantDark,
    onSecondaryContainer = GbTextPrimaryDark,
    tertiary = LifeRefinement,
    onTertiary = Color.White,
    background = GbBgDark,
    onBackground = GbTextPrimaryDark,
    surface = GbSurfaceDark,
    onSurface = GbTextPrimaryDark,
    surfaceVariant = GbSurfaceVariantDark,
    onSurfaceVariant = GbTextSecondaryDark,
    outline = GbBorderDark,
    outlineVariant = GbBorderDark.copy(alpha = 0.5f),
    error = LifeProblem,
    onError = Color.White,
    errorContainer = LifeProblem.copy(alpha = 0.2f),
    onErrorContainer = LifeProblemBorder
)

val LifeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun LifeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) LifeDarkColorScheme else LifeLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = LifeShapes,
        content = content
    )
}

